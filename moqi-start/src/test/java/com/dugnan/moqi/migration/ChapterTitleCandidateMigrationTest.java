package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 验证 V50 可空标题和标题候选的持久化约束。
 */
class ChapterTitleCandidateMigrationTest {

    @Test
    void makesTitleNullableAndPersistsRecoverableCandidateBatches() throws IOException {
        String sql = new ClassPathResource("db/migration/V50__add_chapter_title_candidates.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("MODIFY COLUMN title VARCHAR(255) NULL")
                .contains("CREATE TABLE chapter_title_candidate_batches")
                .contains("source_content_snapshot LONGTEXT NOT NULL")
                .contains("UNIQUE KEY uk_title_batch_idempotency (chapter_id, idempotency_key)")
                .contains("CREATE TABLE chapter_title_candidates")
                .contains("UNIQUE KEY uk_title_candidate_order (batch_id, candidate_order)")
                .contains("UNIQUE KEY uk_title_candidate_adoption_key (adoption_idempotency_key)");
    }
}
