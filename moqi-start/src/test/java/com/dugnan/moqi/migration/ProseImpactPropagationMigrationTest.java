package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ProseImpactPropagationMigrationTest {
    @Test
    void v41AddsImpactEvidenceAndReleaseKnowledgeSourceBoundaries() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/V41__add_prose_impact_propagation.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE prose_revision_impact_reports")
                .contains("CREATE TABLE prose_revision_fact_changes")
                .contains("CREATE TABLE prose_revision_impacted_assets")
                .contains("CREATE TABLE story_release_knowledge_sources")
                .contains("source_prose_revision_id")
                .contains("source_story_release_id")
                .contains("uk_current_knowledge_source")
                .contains("fk_prose_impact_run");
    }

    @Test
    void v42BindsImpactReportsToSourceGraphAndAffectedChapterEvidence() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/V42__bind_prose_impact_source_graph.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("ALTER TABLE prose_revision_impact_reports")
                .contains("source_graph_fingerprint CHAR(64)")
                .contains("ALTER TABLE prose_revision_fact_changes")
                .contains("affected_chapter_ids_json JSON");
    }
}
