package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-12
 * @description 验证场景修订草稿与章纲候选来源链数据库迁移。
 */
class SceneOutlineRevisionBridgeMigrationTest {

    @Test
    void addsNullableTaskAndRecoverableSourceChain() throws Exception {
        String sql = new ClassPathResource("db/migration/V32__add_scene_outline_revision_bridge.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("MODIFY COLUMN ai_task_id BIGINT NULL")
                .contains("source_scene_plan_id BIGINT NULL")
                .contains("source_scene_plan_version INT NULL")
                .contains("revision_idempotency_key VARCHAR(128) NULL")
                .contains("source_consistency_report_id BIGINT NULL")
                .contains("scene_diff_json JSON NULL")
                .contains("uk_chapter_plan_revision_idempotency");
    }
}
