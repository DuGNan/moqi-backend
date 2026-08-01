package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 验证 V14 创建 Agent Runtime 的完整持久化契约。
 */
class AgentRuntimeMigrationTest {

    @Test
    void createsRecoverableAgentRuntimeTables() throws Exception {
        String sql = new ClassPathResource("db/migration/V14__add_agent_runtime_foundation.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE agent_runs")
                .contains("UNIQUE KEY uk_agent_runs_user_workflow_idempotency")
                .contains("CREATE TABLE agent_run_steps")
                .contains("UNIQUE KEY uk_agent_run_steps_run_key_attempt")
                .contains("CREATE TABLE agent_checkpoints")
                .contains("UNIQUE KEY uk_agent_checkpoints_run_sequence")
                .contains("CREATE TABLE agent_interruptions")
                .contains("resume_token_hash CHAR(64) NOT NULL");
    }
}
