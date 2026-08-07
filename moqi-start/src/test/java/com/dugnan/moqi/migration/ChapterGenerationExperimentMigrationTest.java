package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-07
 * @description 验证章节生成策略实验表的隔离字段、幂等约束和查询索引。
 */
class ChapterGenerationExperimentMigrationTest {

    @Test
    void createsIsolatedExperimentTableAndUniqueSampleConstraint() throws Exception {
        String sql = new ClassPathResource("db/migration/V30__add_chapter_generation_experiments.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "chapter_generation_experiments",
                "experiment_group_key",
                "input_fingerprint",
                "model_call_ids_json",
                "raw_scene_outputs_json",
                "uk_chapter_generation_experiment_sample",
                "idx_chapter_generation_experiment_list");
        assertThat(sql).doesNotContain("ALTER TABLE chapters", "ALTER TABLE chapter_generations");
    }
}
