package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 验证章节讨论结构化交互字段的迁移约束。
 */
class DiscussionMessageInteractionMigrationTest {

    @Test
    void addsNullableInteractionColumnsForBackwardCompatibility() throws IOException {
        String sql = new ClassPathResource("db/migration/V34__add_discussion_message_interactions.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE chapter_conversation_messages")
                .contains("ADD COLUMN interaction_json JSON NULL")
                .contains("ADD COLUMN interaction_response_json JSON NULL");
    }
}
