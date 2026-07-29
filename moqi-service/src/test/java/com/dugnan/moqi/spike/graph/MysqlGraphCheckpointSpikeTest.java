package com.dugnan.moqi.spike.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderCapabilities;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseMetadata;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Graph Runtime MySQL checkpoint 隔离验证测试。
 *
 * @author DuGN
 * @date 2026-07-29
 * @description 验证内置 MysqlSaver 的恢复风险及确定性 JSON checkpoint 方案。
 */
@EnabledIfSystemProperty(named = "moqi.graph.mysql.url", matches = ".+")
class MysqlGraphCheckpointSpikeTest {

    private static final GraphRuntimeSpike.SpikeRun RUN =
            new GraphRuntimeSpike.SpikeRun(42L, 4202L, "验证 MySQL checkpoint");

    private MysqlDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new MysqlDataSource();
        dataSource.setURL(System.getProperty("moqi.graph.mysql.url"));
        dataSource.setUser(System.getProperty("moqi.graph.mysql.user", "root"));
        dataSource.setPassword(System.getProperty("moqi.graph.mysql.password", ""));
    }

    @Test
    void exposesBuiltInMysqlSaverNondeterministicRestartOrderingRisk() throws Exception {
        CountingFakeProvider provider = new CountingFakeProvider();
        GraphRuntimeSpike firstRuntime = new GraphRuntimeSpike(
                provider,
                MysqlSaver.builder()
                        .dataSource(dataSource)
                        .createOption(CreateOption.CREATE_OR_REPLACE)
                        .build());

        GraphRuntimeSpike.SpikeExecution interrupted = firstRuntime.start(RUN, ignored -> { });

        assertThat(interrupted.status()).isEqualTo(GraphRuntimeSpike.SpikeStatus.INTERRUPTED);
        assertThat(countRows("GRAPH_THREAD")).isEqualTo(1);
        assertThat(countRows("GRAPH_CHECKPOINT")).isGreaterThanOrEqualTo(4);
        assertThat(activeThreadName()).isEqualTo(RUN.threadId());

        GraphRuntimeSpike restartedRuntime = new GraphRuntimeSpike(
                provider,
                MysqlSaver.builder().dataSource(dataSource).build());
        GraphRuntimeSpike.SpikeExecution completed = restartedRuntime.resume(RUN, ignored -> { });

        assertThat(completed.status()).isIn(
                GraphRuntimeSpike.SpikeStatus.COMPLETED,
                GraphRuntimeSpike.SpikeStatus.INTERRUPTED);
        assertThat(provider.streamCalls()).isGreaterThanOrEqualTo(1);
        assertThat(singleTimestampCheckpointCount()).isGreaterThan(1);
        assertThat(hasCheckpointSequenceColumn()).isFalse();
    }

    @Test
    void restoresInterruptedRunWithDeterministicJsonSaver() throws Exception {
        CountingFakeProvider provider = new CountingFakeProvider();
        GraphRuntimeSpike firstRuntime = new GraphRuntimeSpike(
                provider,
                DeterministicMysqlCheckpointSaver.recreate(dataSource));
        GraphRuntimeSpike.SpikeExecution interrupted = firstRuntime.start(RUN, ignored -> { });

        assertThat(interrupted.status()).isEqualTo(GraphRuntimeSpike.SpikeStatus.INTERRUPTED);

        GraphRuntimeSpike restartedRuntime = new GraphRuntimeSpike(
                provider,
                DeterministicMysqlCheckpointSaver.reopen(dataSource));
        GraphRuntimeSpike.SpikeExecution completed = restartedRuntime.resume(RUN, ignored -> { });

        assertThat(completed.status()).isEqualTo(GraphRuntimeSpike.SpikeStatus.COMPLETED);
        assertThat(completed.state())
                .containsEntry("draftText", "mysql-checkpoint")
                .containsEntry("status", "completed");
        assertThat(provider.streamCalls()).isEqualTo(1);
    }

    @Test
    void rejectsCorruptedSerializedCheckpointInsteadOfStartingFromEmptyState() throws Exception {
        CountingFakeProvider provider = new CountingFakeProvider();
        GraphRuntimeSpike firstRuntime = new GraphRuntimeSpike(
                provider,
                MysqlSaver.builder()
                        .dataSource(dataSource)
                        .createOption(CreateOption.CREATE_OR_REPLACE)
                        .build());
        firstRuntime.start(RUN, ignored -> { });
        corruptLatestCheckpoint();

        GraphRuntimeSpike restartedRuntime = new GraphRuntimeSpike(
                provider,
                MysqlSaver.builder().dataSource(dataSource).build());

        assertThatThrownBy(() -> restartedRuntime.restoredState(RUN))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("base64");
    }

    private int countRows(String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private String activeThreadName() throws SQLException {
        String sql = "SELECT thread_name FROM GRAPH_THREAD WHERE is_released = FALSE";
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private int singleTimestampCheckpointCount() throws SQLException {
        String sql = """
                SELECT MAX(checkpoint_count)
                FROM (
                    SELECT COUNT(*) AS checkpoint_count
                    FROM GRAPH_CHECKPOINT
                    GROUP BY saved_at
                ) grouped_checkpoints
                """;
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private boolean hasCheckpointSequenceColumn() throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'GRAPH_CHECKPOINT'
                  AND COLUMN_NAME IN ('sequence_id', 'id')
                """;
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1) > 0;
        }
    }

    private void corruptLatestCheckpoint() throws SQLException {
        String sql = """
                UPDATE GRAPH_CHECKPOINT
                SET state_data = '{"binaryPayload":"not-base64"}'
                ORDER BY saved_at DESC
                LIMIT 1
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static final class CountingFakeProvider implements LlmProvider {

        private final AtomicInteger streamCalls = new AtomicInteger();

        @Override
        public LlmResponse generate(LlmRequest request) {
            throw new UnsupportedOperationException("Spike 仅验证流式 Provider");
        }

        @Override
        public LlmStreamCall stream(LlmRequest request, Consumer<LlmStreamEvent> consumer) {
            streamCalls.incrementAndGet();
            List.of("mysql-", "checkpoint")
                    .forEach(chunk -> consumer.accept(new LlmStreamEvent.TextDelta(chunk)));
            consumer.accept(new LlmStreamEvent.Completed(metadata()));
            return new CompletedFakeCall();
        }

        @Override
        public LlmProviderCapabilities capabilities() {
            return new LlmProviderCapabilities(true, false, false, 4096, 1024);
        }

        @Override
        public void testConnection() {
        }

        int streamCalls() {
            return streamCalls.get();
        }
    }

    private static final class CompletedFakeCall implements LlmStreamCall {

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(LlmStreamStatus.COMPLETED, metadata(), null);
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }

    private static LlmResponseMetadata metadata() {
        return new LlmResponseMetadata("fake", "fake-model", "stop", 3, 2, 5, "fake-mysql");
    }
}
