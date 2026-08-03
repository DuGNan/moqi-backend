ALTER TABLE chapter_conversation_messages
    ADD COLUMN referenced_message_id BIGINT NULL COMMENT '显式引用的同会话消息 ID' AFTER focus_decision_key;

CREATE INDEX idx_chapter_conversation_messages_reference
    ON chapter_conversation_messages (conversation_id, referenced_message_id);
