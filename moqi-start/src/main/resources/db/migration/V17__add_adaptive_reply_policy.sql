CREATE TABLE reply_policy_preferences (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL COMMENT '所属用户ID',
    scope_type VARCHAR(32) NOT NULL COMMENT 'user/work/chapter/conversation',
    scope_id BIGINT NOT NULL DEFAULT 0 COMMENT '作用域资源ID，user作用域固定为0',
    reply_depth VARCHAR(32) NOT NULL COMMENT 'brief/balanced/deep',
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_reply_policy_preference_scope (user_id, scope_type, scope_id, deleted),
    KEY idx_reply_policy_preferences_lookup (user_id, scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE llm_model_calls
    ADD COLUMN ai_task_id BIGINT NULL AFTER generation_scene_id,
    ADD COLUMN conversation_id BIGINT NULL AFTER ai_task_id,
    ADD COLUMN reply_mode VARCHAR(32) NULL AFTER conversation_id,
    ADD COLUMN reply_depth VARCHAR(32) NULL AFTER reply_mode,
    ADD COLUMN reply_scope_summary VARCHAR(500) NULL AFTER reply_depth,
    ADD COLUMN control_source VARCHAR(32) NULL AFTER reply_scope_summary,
    ADD COLUMN policy_version VARCHAR(64) NULL AFTER control_source,
    ADD KEY idx_llm_model_calls_task_status (ai_task_id, call_status, id),
    ADD KEY idx_llm_model_calls_conversation (conversation_id, id),
    ADD CONSTRAINT fk_llm_model_calls_ai_task_id
        FOREIGN KEY (ai_task_id) REFERENCES ai_tasks (id),
    ADD CONSTRAINT fk_llm_model_calls_conversation_id
        FOREIGN KEY (conversation_id) REFERENCES chapter_conversations (id);
