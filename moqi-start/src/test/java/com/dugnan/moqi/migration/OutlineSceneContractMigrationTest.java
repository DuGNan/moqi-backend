package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 验证章纲和场景规划 V2 契约迁移保留旧数据复核标记。
 */
class OutlineSceneContractMigrationTest {

    /**
     * 迁移必须新增版本和复核列，且不得无声改写既有 JSON。
     */
    @Test
    void addsSchemaAndReviewColumnsForLegacyData() throws Exception {
        String sql = new ClassPathResource("db/migration/V19__version_outline_scene_contract.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(sql).contains("content_schema_version")
                .contains("migration_review_status")
                .contains("outline_content_schema_version")
                .contains("review_required")
                .contains("legacy_outline_v1");
    }
}
