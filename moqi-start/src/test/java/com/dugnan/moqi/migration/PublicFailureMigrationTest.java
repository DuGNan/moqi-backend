package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 验证异步任务安全诊断引用的 V46 数据库契约。
 */
class PublicFailureMigrationTest {

    @Test
    void addsNullableUniqueDiagnosticRefsToTasksAndRuns() throws Exception {
        String sql = new ClassPathResource("db/migration/V46__add_async_diagnostic_refs.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE ai_tasks")
                .contains("ADD COLUMN diagnostic_ref VARCHAR(64) NULL")
                .contains("uk_ai_tasks_diagnostic_ref")
                .contains("ALTER TABLE agent_runs")
                .contains("uk_agent_runs_diagnostic_ref");
    }
}
