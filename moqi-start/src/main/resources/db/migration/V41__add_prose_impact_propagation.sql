CREATE TABLE prose_revision_impact_reports (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    workspace_id BIGINT NULL,
    baseline_revision_id BIGINT NULL,
    target_revision_id BIGINT NOT NULL,
    baseline_release_id BIGINT NULL,
    agent_run_id BIGINT NULL,
    model_call_id BIGINT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    analyzer_version VARCHAR(64) NOT NULL,
    report_status VARCHAR(32) NOT NULL,
    impact_scope VARCHAR(32) NULL,
    blocking TINYINT NOT NULL DEFAULT 1,
    summary_json JSON NULL,
    error_code VARCHAR(64) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prose_impact_idempotency (work_id, idempotency_key),
    KEY idx_prose_impact_revision_status (target_revision_id, report_status, id),
    KEY idx_prose_impact_workspace (workspace_id, report_status, id),
    CONSTRAINT fk_prose_impact_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_prose_impact_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_prose_impact_workspace FOREIGN KEY (workspace_id) REFERENCES work_revision_workspaces (id),
    CONSTRAINT fk_prose_impact_baseline_revision FOREIGN KEY (baseline_revision_id)
        REFERENCES chapter_prose_revisions (id),
    CONSTRAINT fk_prose_impact_target_revision FOREIGN KEY (target_revision_id)
        REFERENCES chapter_prose_revisions (id),
    CONSTRAINT fk_prose_impact_baseline_release FOREIGN KEY (baseline_release_id) REFERENCES story_releases (id),
    CONSTRAINT fk_prose_impact_run FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id),
    CONSTRAINT fk_prose_impact_model_call FOREIGN KEY (model_call_id) REFERENCES llm_model_calls (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE prose_revision_fact_changes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    change_key VARCHAR(128) NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    epistemic_status VARCHAR(32) NOT NULL,
    change_kind VARCHAR(32) NOT NULL,
    impact_scope VARCHAR(32) NOT NULL,
    evidence_text LONGTEXT NOT NULL,
    evidence_start_offset INT NOT NULL,
    evidence_end_offset INT NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    direct_dependency TINYINT NOT NULL DEFAULT 1,
    explanation VARCHAR(1000) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prose_fact_change (report_id, change_key),
    CONSTRAINT fk_prose_fact_change_report FOREIGN KEY (report_id)
        REFERENCES prose_revision_impact_reports (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE prose_revision_impacted_assets (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    chapter_id BIGINT NULL,
    asset_type VARCHAR(32) NOT NULL,
    asset_id BIGINT NULL,
    dependency_type VARCHAR(32) NOT NULL,
    validity_status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prose_impacted_asset (report_id, asset_type, asset_id, chapter_id),
    CONSTRAINT fk_prose_impacted_asset_report FOREIGN KEY (report_id)
        REFERENCES prose_revision_impact_reports (id),
    CONSTRAINT fk_prose_impacted_asset_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE story_release_knowledge_sources (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    prose_revision_id BIGINT NOT NULL,
    knowledge_type VARCHAR(32) NOT NULL,
    knowledge_id BIGINT NOT NULL,
    source_candidate_id BIGINT NULL,
    source_status VARCHAR(32) NOT NULL,
    active_marker TINYINT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_release_knowledge_source (release_id, knowledge_type, knowledge_id),
    UNIQUE KEY uk_current_knowledge_source (work_id, knowledge_type, knowledge_id, active_marker),
    KEY idx_release_knowledge_revision (prose_revision_id, source_status),
    CONSTRAINT fk_release_knowledge_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_release_knowledge_release FOREIGN KEY (release_id) REFERENCES story_releases (id),
    CONSTRAINT fk_release_knowledge_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_release_knowledge_revision FOREIGN KEY (prose_revision_id)
        REFERENCES chapter_prose_revisions (id),
    CONSTRAINT fk_release_knowledge_candidate FOREIGN KEY (source_candidate_id)
        REFERENCES story_knowledge_candidates (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE story_knowledge_extraction_batches
    ADD COLUMN source_prose_revision_id BIGINT NULL AFTER generation_id,
    ADD COLUMN source_story_release_id BIGINT NULL AFTER source_prose_revision_id,
    ADD KEY idx_knowledge_extraction_prose_source (source_prose_revision_id, source_story_release_id),
    ADD CONSTRAINT fk_knowledge_extraction_prose_revision FOREIGN KEY (source_prose_revision_id)
        REFERENCES chapter_prose_revisions (id),
    ADD CONSTRAINT fk_knowledge_extraction_story_release FOREIGN KEY (source_story_release_id)
        REFERENCES story_releases (id);
