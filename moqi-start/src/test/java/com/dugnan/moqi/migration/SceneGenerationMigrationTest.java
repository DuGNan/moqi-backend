package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 验证 V16 建立场景生成批次、候选正文与模型调用审计约束。
 */
class SceneGenerationMigrationTest {

    @Test
    void createsRecoverableSceneGenerationTables() throws Exception {
        String sql = new ClassPathResource("db/migration/V16__add_scene_generation_workflow.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(sql).contains("chapter_plan_version_id")
                .contains("uk_chapter_generations_chapter_idempotency")
                .contains("CREATE TABLE chapter_generation_scenes")
                .contains("UNIQUE KEY uk_generation_scene_plan")
                .contains("context_snapshot_id")
                .contains("CREATE TABLE llm_model_calls")
                .contains("provider_request_id")
                .contains("fk_generation_scenes_model_call_id");
    }
}
