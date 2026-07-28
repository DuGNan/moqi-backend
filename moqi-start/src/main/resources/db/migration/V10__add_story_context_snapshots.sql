CREATE TABLE IF NOT EXISTS story_context_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_key VARCHAR(255) NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NULL,
    conversation_id BIGINT NULL,
    profile VARCHAR(32) NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    snapshot_version BIGINT NOT NULL,
    context_window_tokens INT NOT NULL,
    output_reserve_tokens INT NOT NULL,
    input_budget_tokens INT NOT NULL,
    estimated_input_tokens INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    snapshot_json JSON NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_story_context_scope_hash (scope_key, content_hash),
    UNIQUE KEY uk_story_context_scope_version (scope_key, snapshot_version),
    KEY idx_story_context_work_chapter_profile (work_id, chapter_id, profile, gmt_create),
    KEY idx_story_context_conversation (conversation_id),
    CONSTRAINT fk_story_context_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_story_context_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_story_context_conversation_id FOREIGN KEY (conversation_id) REFERENCES chapter_conversations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='故事上下文快照';

ALTER TABLE ai_tasks
    ADD COLUMN context_snapshot_id BIGINT NULL AFTER result_suggestion_id,
    ADD KEY idx_ai_tasks_context_snapshot_id (context_snapshot_id),
    ADD CONSTRAINT fk_ai_tasks_context_snapshot_id
        FOREIGN KEY (context_snapshot_id) REFERENCES story_context_snapshots (id);
