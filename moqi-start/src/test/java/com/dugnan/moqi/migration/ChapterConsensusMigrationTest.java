package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 验证章节共识任务、讨论对焦和大纲绑定的 V11 迁移。
 */
class ChapterConsensusMigrationTest {

    /**
     * 验证 V11 包含可恢复任务结果、成对 focus 引用和大纲共识外键。
     *
     * @throws Exception 迁移资源读取失败
     */
    @Test
    void addsConsensusReferencesAndFocusPairConstraint() throws Exception {
        var resource = new ClassPathResource(
                "db/migration/V11__add_chapter_consensus_references.sql");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("task_input_json JSON NULL")
                .contains("result_brief_id BIGINT NULL")
                .contains("fk_ai_tasks_result_brief_id")
                .contains("focus_brief_id BIGINT NULL")
                .contains("focus_decision_key VARCHAR(64) NULL")
                .contains("chk_ccm_focus_pair CHECK")
                .contains("confirmed_brief_id BIGINT NULL")
                .contains("fk_chapter_outlines_confirmed_brief_id");
    }
}
