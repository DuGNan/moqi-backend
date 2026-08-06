package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-07
 * @description 验证旧预览回填为已替代状态，避免占用正式正文入口。
 */
class SupersededGenerationPreviewMigrationTest {

    @Test
    void backfillsOnlyPreviewsCreatedBeforeAnAcceptedGeneration() throws Exception {
        String sql = new ClassPathResource(
                        "db/migration/V28__supersede_obsolete_chapter_generation_previews.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("preview.generation_status = 'superseded'")
                .contains("candidate.generation_status = 'preview'")
                .contains("accepted.generation_status = 'accepted'")
                .contains("accepted.gmt_create > candidate.gmt_create");
    }
}
