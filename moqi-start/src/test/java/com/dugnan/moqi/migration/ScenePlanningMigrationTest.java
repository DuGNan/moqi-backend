package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 验证 V15 创建三级故事规划的持久化约束。
 */
class ScenePlanningMigrationTest {
    @Test
    void createsVersionedNarrativeAndScenePlanningTables() throws Exception {
        String sql = new ClassPathResource("db/migration/V15__add_versioned_scene_plans.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE work_narrative_plan_versions")
                .contains("UNIQUE KEY uk_work_narrative_plan_current")
                .contains("CREATE TABLE chapter_plan_versions")
                .contains("UNIQUE KEY uk_chapter_plan_current")
                .contains("CREATE TABLE scene_plan_versions")
                .contains("UNIQUE KEY uk_scene_plan_sequence")
                .contains("result_scene_plan_version_id");
    }
}
