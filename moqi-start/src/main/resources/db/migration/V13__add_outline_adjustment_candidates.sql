CREATE TABLE chapter_outline_candidates (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    ai_task_id BIGINT NOT NULL,
    confirmed_brief_id BIGINT NOT NULL,
    base_outline_id BIGINT NOT NULL,
    base_outline_revision INT NOT NULL,
    base_outline_content JSON NOT NULL,
    candidate_status VARCHAR(32) NOT NULL,
    adjustment_instruction VARCHAR(2000) NOT NULL,
    candidate_content JSON NULL,
    diff_json JSON NULL,
    consensus_impact_json JSON NULL,
    result_outline_id BIGINT NULL,
    result_outline_revision INT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chapter_outline_candidates_ai_task_id (ai_task_id),
    KEY idx_outline_candidates_chapter_id (chapter_id, id),
    KEY idx_outline_candidates_brief_id (confirmed_brief_id),
    CONSTRAINT fk_outline_candidates_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_outline_candidates_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_outline_candidates_conversation_id FOREIGN KEY (conversation_id) REFERENCES chapter_conversations (id),
    CONSTRAINT fk_outline_candidates_ai_task_id FOREIGN KEY (ai_task_id) REFERENCES ai_tasks (id),
    CONSTRAINT fk_outline_candidates_brief_id FOREIGN KEY (confirmed_brief_id) REFERENCES chapter_briefs (id),
    CONSTRAINT fk_outline_candidates_base_outline_id FOREIGN KEY (base_outline_id) REFERENCES chapter_outlines (id),
    CONSTRAINT fk_outline_candidates_result_outline_id FOREIGN KEY (result_outline_id) REFERENCES chapter_outlines (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE ai_tasks
    ADD COLUMN result_outline_candidate_id BIGINT NULL AFTER result_brief_id,
    ADD KEY idx_ai_tasks_result_outline_candidate_id (result_outline_candidate_id),
    ADD CONSTRAINT fk_ai_tasks_result_outline_candidate_id
        FOREIGN KEY (result_outline_candidate_id) REFERENCES chapter_outline_candidates (id);
