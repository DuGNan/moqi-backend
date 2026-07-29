package com.dugnan.moqi.spike.graph;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedList;
import java.util.Map;

import javax.sql.DataSource;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 用于证明确定性排序和 JSON 状态边界的测试 Saver，不属于生产实现。
 *
 * @author DuGN
 * @date 2026-07-29
 * @description 使用单调序列和 JSON 状态验证可恢复 checkpoint 的最小数据边界。
 */
final class DeterministicMysqlCheckpointSaver extends MemorySaver {

    private static final String CREATE_THREAD_TABLE = """
            CREATE TABLE IF NOT EXISTS MOQI_GRAPH_SPIKE_THREAD (
                thread_name VARCHAR(255) PRIMARY KEY,
                is_released BOOLEAN NOT NULL DEFAULT FALSE
            )
            """;
    private static final String CREATE_CHECKPOINT_TABLE = """
            CREATE TABLE IF NOT EXISTS MOQI_GRAPH_SPIKE_CHECKPOINT (
                sequence_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                checkpoint_id VARCHAR(36) NOT NULL UNIQUE,
                thread_name VARCHAR(255) NOT NULL,
                node_id VARCHAR(255),
                next_node_id VARCHAR(255),
                state_json JSON NOT NULL,
                CONSTRAINT FK_MOQI_GRAPH_SPIKE_THREAD
                    FOREIGN KEY (thread_name)
                    REFERENCES MOQI_GRAPH_SPIKE_THREAD(thread_name)
                    ON DELETE CASCADE
            )
            """;
    private static final String DROP_CHECKPOINT_TABLE =
            "DROP TABLE IF EXISTS MOQI_GRAPH_SPIKE_CHECKPOINT";
    private static final String DROP_THREAD_TABLE =
            "DROP TABLE IF EXISTS MOQI_GRAPH_SPIKE_THREAD";
    private static final String SELECT_CHECKPOINTS = """
            SELECT checkpoint_id, node_id, next_node_id, state_json
            FROM MOQI_GRAPH_SPIKE_CHECKPOINT
            WHERE thread_name = ?
            ORDER BY sequence_id DESC
            """;
    private static final String INSERT_THREAD = """
            INSERT INTO MOQI_GRAPH_SPIKE_THREAD(thread_name, is_released)
            VALUES (?, FALSE)
            ON DUPLICATE KEY UPDATE thread_name = thread_name
            """;
    private static final String INSERT_CHECKPOINT = """
            INSERT INTO MOQI_GRAPH_SPIKE_CHECKPOINT(
                checkpoint_id, thread_name, node_id, next_node_id, state_json)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String UPDATE_CHECKPOINT = """
            UPDATE MOQI_GRAPH_SPIKE_CHECKPOINT
            SET checkpoint_id = ?, node_id = ?, next_node_id = ?, state_json = ?
            WHERE checkpoint_id = ?
            """;
    private static final String RELEASE_THREAD = """
            UPDATE MOQI_GRAPH_SPIKE_THREAD
            SET is_released = TRUE
            WHERE thread_name = ? AND is_released = FALSE
            """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DeterministicMysqlCheckpointSaver(DataSource dataSource, boolean recreateTables) {
        this.dataSource = dataSource;
        initializeTables(recreateTables);
    }

    static DeterministicMysqlCheckpointSaver recreate(DataSource dataSource) {
        return new DeterministicMysqlCheckpointSaver(dataSource, true);
    }

    static DeterministicMysqlCheckpointSaver reopen(DataSource dataSource) {
        return new DeterministicMysqlCheckpointSaver(dataSource, false);
    }

    @Override
    protected LinkedList<Checkpoint> loadedCheckpoints(
            RunnableConfig config,
            LinkedList<Checkpoint> checkpoints) throws Exception {
        if (!checkpoints.isEmpty()) {
            return checkpoints;
        }
        String threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_CHECKPOINTS)) {
            statement.setString(1, threadId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    checkpoints.add(Checkpoint.builder()
                            .id(resultSet.getString("checkpoint_id"))
                            .nodeId(resultSet.getString("node_id"))
                            .nextNodeId(resultSet.getString("next_node_id"))
                            .state(readState(resultSet.getString("state_json")))
                            .build());
                }
            }
        }
        return checkpoints;
    }

    @Override
    protected void insertedCheckpoint(
            RunnableConfig config,
            LinkedList<Checkpoint> checkpoints,
            Checkpoint checkpoint) throws Exception {
        String threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement threadStatement = connection.prepareStatement(INSERT_THREAD);
                    PreparedStatement checkpointStatement =
                            connection.prepareStatement(INSERT_CHECKPOINT)) {
                threadStatement.setString(1, threadId);
                threadStatement.executeUpdate();
                bindCheckpoint(checkpointStatement, checkpoint, threadId);
                checkpointStatement.executeUpdate();
                connection.commit();
            } catch (SQLException | JsonProcessingException exception) {
                rollback(connection, exception);
            }
        }
    }

    @Override
    protected void updatedCheckpoint(
            RunnableConfig config,
            LinkedList<Checkpoint> checkpoints,
            Checkpoint checkpoint) throws Exception {
        String previousCheckpointId = config.checkPointId().orElseThrow(() ->
                new IllegalStateException("缺少待更新 checkpointId"));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(UPDATE_CHECKPOINT)) {
            statement.setString(1, checkpoint.getId());
            statement.setString(2, checkpoint.getNodeId());
            statement.setString(3, checkpoint.getNextNodeId());
            statement.setString(4, writeState(checkpoint.getState()));
            statement.setString(5, previousCheckpointId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("checkpoint 更新行数异常");
            }
        }
    }

    @Override
    protected void releasedCheckpoints(
            RunnableConfig config,
            LinkedList<Checkpoint> checkpoints,
            Tag releaseTag) throws Exception {
        String threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(RELEASE_THREAD)) {
            statement.setString(1, threadId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("thread 释放行数异常");
            }
        }
    }

    private void initializeTables(boolean recreateTables) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            if (recreateTables) {
                statement.execute(DROP_CHECKPOINT_TABLE);
                statement.execute(DROP_THREAD_TABLE);
            }
            statement.execute(CREATE_THREAD_TABLE);
            statement.execute(CREATE_CHECKPOINT_TABLE);
        } catch (SQLException exception) {
            throw new IllegalStateException("初始化 Spike checkpoint 表失败", exception);
        }
    }

    private void bindCheckpoint(
            PreparedStatement statement,
            Checkpoint checkpoint,
            String threadId) throws SQLException, JsonProcessingException {
        statement.setString(1, checkpoint.getId());
        statement.setString(2, threadId);
        statement.setString(3, checkpoint.getNodeId());
        statement.setString(4, checkpoint.getNextNodeId());
        statement.setString(5, writeState(checkpoint.getState()));
    }

    private String writeState(Map<String, Object> state) throws JsonProcessingException {
        return objectMapper.writeValueAsString(state);
    }

    private Map<String, Object> readState(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, new TypeReference<>() { });
    }

    private void rollback(Connection connection, Exception originalException) throws Exception {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
        throw originalException;
    }
}
