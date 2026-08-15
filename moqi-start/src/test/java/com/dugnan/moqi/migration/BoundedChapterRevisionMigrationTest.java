package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证整章有界修订迁移包含唯一轮次、候选和重新评价约束。
 */
class BoundedChapterRevisionMigrationTest {

    @Test
    void addsBoundedRevisionTraceabilityAndOneRoundConstraint() throws Exception {
        String sql = new ClassPathResource("db/migration/V39__add_bounded_chapter_revisions.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE bounded_chapter_revisions")
                .contains("source_report_id BIGINT NOT NULL")
                .contains("result_generation_id BIGINT NULL")
                .contains("result_report_id BIGINT NULL")
                .contains("revision_brief_json JSON NOT NULL")
                .contains("UNIQUE KEY uk_bounded_revision_source_generation (source_generation_id)")
                .contains("UNIQUE KEY uk_bounded_revision_source_report (source_report_id)");
    }
}
