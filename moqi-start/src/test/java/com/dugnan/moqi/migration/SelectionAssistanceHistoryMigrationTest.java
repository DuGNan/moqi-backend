package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 验证选区协助冻结正文对象会话历史以支持确定性重放。
 */
class SelectionAssistanceHistoryMigrationTest {

    @Test
    void addsFrozenConversationHistoryWithoutBackfillingUnrelatedMessages() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V52__freeze_selection_assistance_history.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("conversation_history_json LONGTEXT")
                .contains("chapter_selection_assistance")
                .doesNotContain("UPDATE chapter_selection_assistance");
    }
}
