package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:验证 V8 迁移包含长篇小说知识层表结构。
 */
class StoryKnowledgeMigrationTest {

    /**
     * 验证知识层迁移创建所需表结构和章节类型字段。
     *
     * @throws IOException 读取迁移文件失败
     */
    @Test
    void v8MigrationAddsStoryKnowledgeLayerTablesAndChapterType() throws IOException {
        var resource = new ClassPathResource("db/migration/V8__add_story_knowledge_layer_tables.sql");

        assertThat(resource.exists()).isTrue();

        var sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE chapters")
                .contains("ADD COLUMN chapter_type")
                .contains("CREATE TABLE IF NOT EXISTS setting_candidates")
                .contains("CREATE TABLE IF NOT EXISTS setting_entries")
                .contains("CREATE TABLE IF NOT EXISTS foreshadowing_items")
                .contains("CREATE TABLE IF NOT EXISTS chapter_summaries")
                .contains("CREATE TABLE IF NOT EXISTS chapter_key_events")
                .contains("fk_setting_candidates_work_id")
                .contains("fk_setting_entries_work_id")
                .contains("fk_foreshadowing_items_source_chapter_id")
                .contains("fk_chapter_summaries_chapter_id")
                .contains("fk_chapter_key_events_chapter_id")
                .contains("idx_setting_candidates_work_status")
                .contains("idx_setting_entries_work_type")
                .contains("idx_foreshadowing_items_work_status")
                .contains("idx_chapter_summaries_work_gmt_modified")
                .contains("idx_chapter_key_events_work_chapter");
    }
}
