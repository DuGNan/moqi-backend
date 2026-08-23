ALTER TABLE chapter_selection_assistance
    ADD COLUMN planning_context_json JSON NULL AFTER assistant_message_id,
    ADD COLUMN applied_candidate_version INT NULL AFTER planning_context_json,
    ADD COLUMN applied_candidate_hash CHAR(64) NULL AFTER applied_candidate_version,
    ADD KEY idx_selection_assistance_proposal_gate (
        chapter_id,
        target_candidate_id,
        proposal_status,
        id
    );
