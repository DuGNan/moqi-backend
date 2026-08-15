package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证不可变正文 revision、修订工作区和 Story Release 的数据库契约。
 */
class StoryReleaseRevisionMigrationTest {

    @Test
    void freezesRevisionWorkspaceAndAtomicReleaseContracts() throws Exception {
        String sql = new ClassPathResource("db/migration/V40__add_story_release_revisions.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE chapter_prose_revisions")
                .contains("CREATE TABLE story_releases")
                .contains("CREATE TABLE story_release_chapters")
                .contains("CREATE TABLE work_revision_workspaces")
                .contains("CREATE TABLE work_revision_workspace_chapters")
                .contains("current_story_release_id")
                .contains("current_prose_revision_id")
                .contains("UNIQUE KEY uk_story_release_current (work_id, current_marker)")
                .contains("UNIQUE KEY uk_workspace_current (work_id, current_marker)");
    }
}
