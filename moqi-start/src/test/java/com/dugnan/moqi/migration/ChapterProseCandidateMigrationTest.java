package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 验证稳定正文候选、工作区选择和历史生成回填的数据库契约。
 */
class ChapterProseCandidateMigrationTest {

    @Test
    void createsStableCandidateCatalogAndBackfillsVisibleHistory() throws Exception {
        String sql = new ClassPathResource("db/migration/V44__add_chapter_prose_candidates.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE chapter_prose_candidates")
                .contains("CREATE TABLE chapter_prose_workspace_selections")
                .contains("UNIQUE KEY uk_prose_candidate_source_generation (source_generation_id)")
                .contains("UNIQUE KEY uk_prose_workspace_selection_chapter (chapter_id)")
                .contains("INSERT INTO chapter_prose_candidates")
                .contains("generation.generation_status IN ('preview', 'accepted', 'rejected', 'superseded')")
                .contains("quality_request_status")
                .contains("WITH RECURSIVE candidate_tree AS")
                .contains("JOIN candidate_tree parent ON parent.id = child.parent_candidate_id")
                .contains("SET candidate.root_candidate_id = tree.root_candidate_id");
    }

    @Test
    void repairsSceneOnlyReportsThatWereMisclassifiedAsWholeChapterQuality() throws Exception {
        String sql = new ClassPathResource("db/migration/V45__repair_prose_candidate_quality_scope.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("generation_scene_id IS NULL")
                .contains("quality_request_status = 'requested'")
                .contains("quality_request_status = 'unavailable'");
    }
}
