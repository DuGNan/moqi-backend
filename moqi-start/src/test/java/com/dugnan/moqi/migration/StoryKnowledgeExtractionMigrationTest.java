package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 验证 V27 知识提取批次、候选、来源和幂等约束。
 */
class StoryKnowledgeExtractionMigrationTest {

    @Test
    void createsExtractionBatchAndCandidateTables() throws Exception {
        String sql = new String(
                new ClassPathResource(
                        "db/migration/V27__add_story_knowledge_extraction_workflow.sql")
                        .getInputStream()
                        .readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE story_knowledge_extraction_batches")
                .contains("CREATE TABLE story_knowledge_candidates")
                .contains("source_fingerprint")
                .contains("extractor_version")
                .contains("idempotency_key")
                .contains("evidence_start_offset")
                .contains("confirmed_target_id")
                .contains("UNIQUE KEY");
    }
}
