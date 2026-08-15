package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证章节容量评估迁移包含冻结来源、运行状态和幂等约束。
 */
class ChapterCapacityAssessmentMigrationTest {

    @Test
    void createsFrozenAndRecoverableCapacityAssessmentStorage() throws Exception {
        String sql = new ClassPathResource("db/migration/V35__add_chapter_capacity_assessments.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE chapter_capacity_assessments")
                .contains("source_snapshot_json JSON NOT NULL")
                .contains("input_fingerprint CHAR(64) NOT NULL")
                .contains("result_json JSON NULL")
                .contains("UNIQUE KEY uk_capacity_assessment_idempotency")
                .contains("FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id)");
    }
}
