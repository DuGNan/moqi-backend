ALTER TABLE chapter_outline_candidates
    ADD COLUMN candidate_type VARCHAR(32) NOT NULL DEFAULT 'adjustment' AFTER confirmed_brief_id,
    ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER candidate_type,
    MODIFY COLUMN base_outline_id BIGINT NULL,
    MODIFY COLUMN base_outline_revision INT NULL,
    MODIFY COLUMN base_outline_content JSON NULL,
    ADD UNIQUE KEY uk_outline_candidates_chapter_idempotency (chapter_id, idempotency_key),
    ADD KEY idx_outline_candidates_active_initial (chapter_id, candidate_type, candidate_status, id);
