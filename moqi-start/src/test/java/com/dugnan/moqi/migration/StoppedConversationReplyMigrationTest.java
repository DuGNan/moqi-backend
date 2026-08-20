package com.dugnan.moqi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * @author dgn
 * @date 2026-08-19
 * @description 验证停止回复持久化与幂等重试链的迁移约束。
 */
class StoppedConversationReplyMigrationTest {

    @Test
    void addsGenerationStatusAndUniqueRetrySource() throws IOException {
        String sql = new ClassPathResource("db/migration/V43__persist_stopped_conversation_replies.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ADD COLUMN generation_status VARCHAR(32) NULL")
                .contains("SET generation_status = 'completed'")
                .contains("ADD COLUMN retry_of_task_id BIGINT NULL")
                .contains("UNIQUE KEY uk_ai_tasks_retry_of_task_id (retry_of_task_id)")
                .contains("FOREIGN KEY (retry_of_task_id) REFERENCES ai_tasks (id)");
    }
}
