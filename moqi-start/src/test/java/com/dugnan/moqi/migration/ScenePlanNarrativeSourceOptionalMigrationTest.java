package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-13
 * @description 验证场景规划不再强制依赖作品叙事规划的数据库迁移。
 */
class ScenePlanNarrativeSourceOptionalMigrationTest {

    @Test
    void makesHistoricalNarrativeSourceColumnsOptional() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V33__make_scene_plan_narrative_source_optional.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("MODIFY COLUMN narrative_plan_id BIGINT NULL")
                .contains("MODIFY COLUMN narrative_plan_no INT NULL");
    }
}
