package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class StoryContextMigrationTest {

    @Test
    void createsVersionedStoryContextSnapshotAndTaskReference() throws Exception {
        var resource = new ClassPathResource("db/migration/V10__add_story_context_snapshots.sql");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS story_context_snapshots")
                .contains("snapshot_version BIGINT NOT NULL")
                .contains("content_hash CHAR(64) NOT NULL")
                .contains("snapshot_json JSON NOT NULL")
                .contains("uk_story_context_scope_hash")
                .contains("uk_story_context_scope_version")
                .contains("ALTER TABLE ai_tasks")
                .contains("context_snapshot_id BIGINT NULL")
                .contains("fk_ai_tasks_context_snapshot_id");
    }
}
