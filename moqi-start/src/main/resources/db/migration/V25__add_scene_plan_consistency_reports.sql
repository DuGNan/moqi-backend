CREATE TABLE scene_plan_consistency_reports (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    chapter_plan_version_id BIGINT NOT NULL,
    plan_version INT NOT NULL,
    source_snapshot_id BIGINT NULL,
    ai_task_id BIGINT NULL,
    agent_run_id BIGINT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    plan_snapshot_json JSON NOT NULL,
    report_status VARCHAR(32) NOT NULL,
    result_status VARCHAR(32) NULL,
    findings_json JSON NULL,
    resolution_status VARCHAR(32) NULL,
    ruleset_version VARCHAR(64) NOT NULL,
    evaluator_version VARCHAR(64) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_scene_plan_consistency_idempotency (chapter_id, idempotency_key),
    KEY idx_scene_plan_consistency_plan (chapter_plan_version_id, id),
    CONSTRAINT fk_scene_plan_consistency_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_scene_plan_consistency_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_scene_plan_consistency_plan FOREIGN KEY (chapter_plan_version_id) REFERENCES chapter_plan_versions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE chapter_plan_versions
    ADD COLUMN published_consistency_report_id BIGINT NULL AFTER validity_reason_codes_json,
    ADD KEY idx_chapter_plan_consistency_report (published_consistency_report_id),
    ADD CONSTRAINT fk_chapter_plan_consistency_report FOREIGN KEY (published_consistency_report_id)
        REFERENCES scene_plan_consistency_reports (id);

ALTER TABLE chapter_briefs
    ADD COLUMN trigger_source VARCHAR(32) NOT NULL DEFAULT 'manual' AFTER brief_status,
    ADD COLUMN base_brief_id BIGINT NULL AFTER trigger_source,
    ADD COLUMN source_asset_type VARCHAR(32) NULL AFTER base_brief_id,
    ADD COLUMN source_asset_id BIGINT NULL AFTER source_asset_type,
    ADD COLUMN source_report_id BIGINT NULL AFTER source_asset_id,
    ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER source_report_id,
    ADD KEY idx_chapter_brief_report (source_report_id),
    ADD UNIQUE KEY uk_chapter_brief_idempotency (chapter_id, trigger_source, idempotency_key),
    ADD CONSTRAINT fk_chapter_brief_report FOREIGN KEY (source_report_id)
        REFERENCES scene_plan_consistency_reports (id);
