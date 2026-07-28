ALTER TABLE ai_tasks
    ADD COLUMN task_input_json JSON NULL AFTER context_snapshot_id,
    ADD COLUMN result_brief_id BIGINT NULL AFTER result_suggestion_id,
    ADD KEY idx_ai_tasks_result_brief_id (result_brief_id),
    ADD CONSTRAINT fk_ai_tasks_result_brief_id
        FOREIGN KEY (result_brief_id) REFERENCES chapter_briefs (id);

ALTER TABLE chapter_conversation_messages
    ADD COLUMN focus_brief_id BIGINT NULL AFTER ai_task_id,
    ADD COLUMN focus_decision_key VARCHAR(64) NULL AFTER focus_brief_id,
    ADD KEY idx_ccm_focus_brief_id (focus_brief_id),
    ADD CONSTRAINT fk_ccm_focus_brief_id
        FOREIGN KEY (focus_brief_id) REFERENCES chapter_briefs (id),
    ADD CONSTRAINT chk_ccm_focus_pair CHECK (
        (focus_brief_id IS NULL AND focus_decision_key IS NULL)
        OR (focus_brief_id IS NOT NULL AND focus_decision_key IS NOT NULL)
    );

ALTER TABLE chapter_outlines
    ADD COLUMN confirmed_brief_id BIGINT NULL AFTER chapter_id,
    ADD KEY idx_chapter_outlines_confirmed_brief_id (confirmed_brief_id),
    ADD CONSTRAINT fk_chapter_outlines_confirmed_brief_id
        FOREIGN KEY (confirmed_brief_id) REFERENCES chapter_briefs (id);
