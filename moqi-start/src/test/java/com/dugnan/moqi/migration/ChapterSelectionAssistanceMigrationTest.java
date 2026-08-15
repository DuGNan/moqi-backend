package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证章节选区协助迁移具备幂等、恢复、来源和采纳追溯字段。
 */
class ChapterSelectionAssistanceMigrationTest {

    @Test
    void createsTraceableSelectionAssistanceTable() throws IOException {
        String sql = new ClassPathResource("db/migration/V36__add_chapter_selection_assistance.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE chapter_selection_assistance")
                .contains("UNIQUE KEY uk_selection_assistance_idempotency")
                .contains("base_content_hash CHAR(64) NOT NULL")
                .contains("brief_fingerprint CHAR(64) NOT NULL")
                .contains("accepted_chapter_version INT NULL")
                .contains("FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id)");
    }
}
