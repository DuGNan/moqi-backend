ALTER TABLE chapter_selection_assistance
    ADD COLUMN target_kind VARCHAR(16) NOT NULL DEFAULT 'formal' AFTER request_status,
    ADD COLUMN request_contract_version INT NOT NULL DEFAULT 1 AFTER target_kind,
    ADD COLUMN target_object_id VARCHAR(64) NULL AFTER request_contract_version,
    ADD COLUMN target_candidate_id BIGINT NULL AFTER target_object_id,
    ADD COLUMN target_content_version INT NULL AFTER target_candidate_id,
    ADD COLUMN target_content_hash CHAR(64) NULL AFTER target_content_version,
    ADD COLUMN reference_scope VARCHAR(16) NOT NULL DEFAULT 'selection' AFTER target_content_hash,
    ADD COLUMN reference_text_hash CHAR(64) NULL AFTER selected_text,
    ADD COLUMN reference_sentence_count INT NULL AFTER reference_text_hash,
    ADD COLUMN reference_snapshot LONGTEXT NULL AFTER reference_sentence_count,
    ADD COLUMN created_candidate_id BIGINT NULL AFTER reference_snapshot,
    ADD COLUMN proposal_status VARCHAR(32) NOT NULL DEFAULT 'pending' AFTER created_candidate_id,
    ADD COLUMN conversation_id BIGINT NULL AFTER proposal_status,
    ADD COLUMN user_message_id BIGINT NULL AFTER conversation_id,
    ADD COLUMN assistant_message_id BIGINT NULL AFTER user_message_id,
    ADD KEY idx_selection_assistance_target (chapter_id, target_kind, target_candidate_id, id),
    ADD KEY idx_selection_assistance_conversation (conversation_id, id),
    ADD CONSTRAINT fk_selection_assistance_target_candidate FOREIGN KEY (target_candidate_id)
        REFERENCES chapter_prose_candidates (id),
    ADD CONSTRAINT fk_selection_assistance_created_candidate FOREIGN KEY (created_candidate_id)
        REFERENCES chapter_prose_candidates (id),
    ADD CONSTRAINT fk_selection_assistance_conversation FOREIGN KEY (conversation_id)
        REFERENCES chapter_conversations (id),
    ADD CONSTRAINT fk_selection_assistance_user_message FOREIGN KEY (user_message_id)
        REFERENCES chapter_conversation_messages (id),
    ADD CONSTRAINT fk_selection_assistance_assistant_message FOREIGN KEY (assistant_message_id)
        REFERENCES chapter_conversation_messages (id);

UPDATE chapter_selection_assistance
SET target_object_id = CONCAT('formal:', chapter_id),
    target_content_version = base_chapter_version,
    target_content_hash = base_content_hash,
    reference_text_hash = SHA2(selected_text, 256),
    reference_sentence_count = GREATEST(
        1,
        CHAR_LENGTH(selected_text)
            - CHAR_LENGTH(REPLACE(REPLACE(REPLACE(REPLACE(selected_text, '。', ''), '！', ''), '？', ''), '\n', ''))
    ),
    reference_snapshot = selected_text,
    proposal_status = CASE
        WHEN request_status IN ('accepted', 'rejected', 'failed', 'canceled') THEN request_status
        WHEN operation_type = 'discuss' AND request_status IN ('ready', 'review_required') THEN 'discussion'
        WHEN request_status IN ('ready', 'review_required') THEN 'ready'
        ELSE 'pending'
    END
WHERE target_object_id IS NULL;

CREATE TABLE prose_planning_change_packages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    assistance_id BIGINT NOT NULL,
    target_candidate_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    package_status VARCHAR(32) NOT NULL,
    change_summary VARCHAR(500) NOT NULL,
    before_summary VARCHAR(1000) NOT NULL,
    after_summary VARCHAR(1000) NOT NULL,
    target_candidate_version INT NOT NULL,
    target_candidate_hash CHAR(64) NOT NULL,
    base_outline_id BIGINT NOT NULL,
    base_outline_revision INT NOT NULL,
    base_outline_version INT NOT NULL,
    base_scene_plan_id BIGINT NOT NULL,
    base_scene_plan_version INT NOT NULL,
    proposed_scenes_json JSON NOT NULL,
    applied_candidate_version INT NULL,
    applied_candidate_hash CHAR(64) NULL,
    result_scene_plan_id BIGINT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prose_planning_package_idempotency (chapter_id, idempotency_key),
    UNIQUE KEY uk_prose_planning_package_assistance (assistance_id),
    KEY idx_prose_planning_package_target (target_candidate_id, package_status, id),
    CONSTRAINT fk_prose_planning_package_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_prose_planning_package_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_prose_planning_package_assistance FOREIGN KEY (assistance_id)
        REFERENCES chapter_selection_assistance (id),
    CONSTRAINT fk_prose_planning_package_candidate FOREIGN KEY (target_candidate_id)
        REFERENCES chapter_prose_candidates (id),
    CONSTRAINT fk_prose_planning_package_outline FOREIGN KEY (base_outline_id) REFERENCES chapter_outlines (id),
    CONSTRAINT fk_prose_planning_package_scene_plan FOREIGN KEY (base_scene_plan_id)
        REFERENCES chapter_plan_versions (id),
    CONSTRAINT fk_prose_planning_package_result_scene_plan FOREIGN KEY (result_scene_plan_id)
        REFERENCES chapter_plan_versions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
