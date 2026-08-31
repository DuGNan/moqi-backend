package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 验证 V51 建立对象会话作用域和消息重放唯一约束。 */
class ProseObjectConversationMigrationTest {

    @Test
    void scopesActiveConversationAndClientMessageId() throws Exception {
        String sql = new ClassPathResource("db/migration/V51__scope_prose_object_conversations.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("target_object_id VARCHAR(96)")
                .contains("conversation_type = 'prose_object'")
                .contains("uk_chapter_conversations_active_prose_scope")
                .contains("client_message_id VARCHAR(128)")
                .contains("uk_ccm_conversation_client_message (conversation_id, client_message_id)")
                .doesNotContain("UPDATE chapter_conversations")
                .doesNotContain("chapter_co_creation' THEN");
    }
}
