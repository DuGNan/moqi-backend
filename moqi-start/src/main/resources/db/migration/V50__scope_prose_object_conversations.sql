ALTER TABLE chapter_conversations
    ADD COLUMN target_object_id VARCHAR(96) NULL COMMENT '正文对象稳定 ID，仅 prose_object 会话使用'
        AFTER conversation_status,
    ADD COLUMN active_prose_scope_key VARCHAR(160)
        GENERATED ALWAYS AS (
            CASE
                WHEN conversation_type = 'prose_object'
                    AND conversation_status = 'active'
                    AND deleted = 0
                    AND target_object_id IS NOT NULL
                THEN CONCAT(chapter_id, ':', target_object_id)
                ELSE NULL
            END
        ) STORED COMMENT '活动正文对象会话唯一作用域',
    ADD UNIQUE KEY uk_chapter_conversations_active_prose_scope (active_prose_scope_key),
    ADD KEY idx_chapter_conversations_prose_target
        (chapter_id, conversation_type, target_object_id, conversation_status, deleted);

ALTER TABLE chapter_conversation_messages
    ADD COLUMN client_message_id VARCHAR(128) NULL COMMENT '客户端消息幂等键'
        AFTER content,
    ADD UNIQUE KEY uk_ccm_conversation_client_message (conversation_id, client_message_id);
