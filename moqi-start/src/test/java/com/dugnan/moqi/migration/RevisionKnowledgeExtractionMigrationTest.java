package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 验证知识提取按 generation/revision 来源分别保持唯一。 */
class RevisionKnowledgeExtractionMigrationTest {

    @Test
    void v54ScopesExtractionUniquenessByImmutableSource() throws Exception {
        String sql = new String(new ClassPathResource(
                "db/migration/V54__scope_knowledge_extraction_by_prose_revision.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ADD KEY idx_knowledge_extraction_generation (generation_id)")
                .contains("DROP INDEX uk_knowledge_extraction_generation_version")
                .contains("source_scope_key VARCHAR(160)")
                .contains("WHEN source_prose_revision_id IS NULL")
                .contains("source_story_release_id AS CHAR")
                .contains("uk_knowledge_extraction_source_version");
        assertThat(sql.indexOf("ADD KEY idx_knowledge_extraction_generation"))
                .isLessThan(sql.indexOf("DROP INDEX uk_knowledge_extraction_generation_version"));
    }
}
