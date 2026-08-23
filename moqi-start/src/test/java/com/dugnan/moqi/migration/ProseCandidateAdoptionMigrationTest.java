package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Update;

import com.dugnan.moqi.chapter.mapper.ProseCandidateAdoptionMapper;

/**
 * @author dgn
 * @date 2026-08-23
 * @description 验证 V49 正文候选采纳记录的幂等唯一约束和恢复索引。
 */
class ProseCandidateAdoptionMigrationTest {

    @Test
    void freezesAdoptionInputAndRecoveryReferences() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V49__add_prose_candidate_adoptions.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).contains("chapter_prose_candidate_adoptions", "candidate_content_hash",
                "quality_report_id", "revision_id", "workspace_id", "impact_report_id",
                "UNIQUE KEY uk_prose_adoption_chapter_key (chapter_id, idempotency_key)",
                "UNIQUE KEY uk_prose_adoption_candidate_version (candidate_id, candidate_version)",
                "KEY idx_prose_adoption_recovery (adoption_status, id)");
    }

    @Test
    void bindsImpactReportOnlyOnceWithoutAdvancingVersionOnReplay() throws Exception {
        Update update = ProseCandidateAdoptionMapper.class
                .getMethod("bindImpactReport", Long.class, Long.class)
                .getAnnotation(Update.class);

        assertThat(update).isNotNull();
        assertThat(String.join(" ", update.value()))
                .contains("adoption_status = 'impact_pending'", "impact_report_id IS NULL");
    }
}
