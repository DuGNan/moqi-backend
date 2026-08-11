package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-11
 * @description 验证场景规划语义内容 schema 版本迁移保留历史 v1 默认值。
 */
class ScenePlanSemanticContentMigrationTest {

    @Test
    void addsSceneContentSchemaVersionWithLegacyDefault() throws Exception {
        String sql = new ClassPathResource("db/migration/V31__version_scene_plan_semantic_content.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("ALTER TABLE scene_plan_versions")
                .contains("content_schema_version INT NOT NULL DEFAULT 1");
    }
}
