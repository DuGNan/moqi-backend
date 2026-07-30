package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 验证大纲调整候选及 AI 任务结果引用迁移。
 */
class OutlineCandidateMigrationTest {

    /**
     * 迁移必须同时提供候选事实表、任务结果引用和必要的关联索引。
     *
     * @throws Exception 资源读取失败
     */
    @Test
    void createsOutlineCandidatesAndTaskResultReference() throws Exception {
        var resource = new ClassPathResource(
                "db/migration/V13__add_outline_adjustment_candidates.sql");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE chapter_outline_candidates")
                .contains("base_outline_content JSON NOT NULL")
                .contains("candidate_status VARCHAR(32) NOT NULL")
                .contains("UNIQUE KEY uk_chapter_outline_candidates_ai_task_id")
                .contains("ADD COLUMN result_outline_candidate_id BIGINT NULL")
                .contains("fk_ai_tasks_result_outline_candidate_id");
    }
}
