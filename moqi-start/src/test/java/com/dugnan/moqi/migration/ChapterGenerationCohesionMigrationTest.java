package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-07
 * @description 验证章节生成批次的整章收束持久化字段迁移。
 */
class ChapterGenerationCohesionMigrationTest {

    @Test
    void addsCohesionMetadataColumnsAndIndexes() throws Exception {
        String sql = new ClassPathResource("db/migration/V29__add_chapter_generation_cohesion.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("content_assembly_mode", "cohesion_status", "cohesion_model_call_id",
                "cohesion_template_version", "idx_chapter_generations_cohesion_status",
                "idx_chapter_generations_cohesion_call");
    }
}
