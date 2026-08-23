package com.dugnan.moqi.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 验证 V47 持久化正文目标引用和原子规划变更包所需约束。
 */
class ProseAssistancePlanningMigrationTest {

    @Test
    void addsRecoverableTargetReferencesAndPlanningPackage() throws IOException {
        String sql = new ClassPathResource("db/migration/V47__add_prose_assistance_planning.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("request_contract_version INT NOT NULL DEFAULT 1")
                .contains("target_object_id VARCHAR(64)")
                .contains("reference_text_hash CHAR(64)")
                .contains("reference_sentence_count INT")
                .contains("reference_snapshot LONGTEXT")
                .contains("CREATE TABLE prose_planning_change_packages")
                .contains("UNIQUE KEY uk_prose_planning_package_idempotency")
                .contains("before_summary VARCHAR(1000) NOT NULL")
                .contains("after_summary VARCHAR(1000) NOT NULL")
                .contains("target_candidate_version INT NOT NULL")
                .contains("target_candidate_hash CHAR(64) NOT NULL")
                .contains("base_outline_revision INT NOT NULL")
                .contains("base_scene_plan_version INT NOT NULL")
                .contains("result_scene_plan_id BIGINT NULL")
                .contains("WHEN request_status IN ('accepted', 'rejected', 'failed', 'canceled') THEN request_status")
                .contains("WHEN operation_type = 'discuss' AND request_status IN ('ready', 'review_required')")
                .doesNotContain("WHEN operation_type = 'discuss' THEN 'discussion'");
    }
}
