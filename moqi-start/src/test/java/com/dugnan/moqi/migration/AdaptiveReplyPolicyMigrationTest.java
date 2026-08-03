package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 验证回复策略偏好与模型审计迁移的关键约束。
 */
class AdaptiveReplyPolicyMigrationTest {

    @Test
    void definesPreferenceScopeAndConversationAudit() throws IOException {
        String sql = new ClassPathResource("db/migration/V17__add_adaptive_reply_policy.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE reply_policy_preferences")
                .contains("UNIQUE KEY uk_reply_policy_preference_scope")
                .contains("ADD COLUMN ai_task_id")
                .contains("ADD COLUMN conversation_id")
                .contains("ADD COLUMN reply_mode")
                .contains("ADD COLUMN reply_depth")
                .contains("ADD COLUMN reply_scope_summary")
                .contains("FOREIGN KEY (ai_task_id) REFERENCES ai_tasks (id)")
                .contains("FOREIGN KEY (conversation_id) REFERENCES chapter_conversations (id)");
    }
}
