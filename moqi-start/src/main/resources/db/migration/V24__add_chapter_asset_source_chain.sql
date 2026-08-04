CREATE TABLE chapter_asset_source_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    asset_type VARCHAR(32) NOT NULL,
    asset_id BIGINT NOT NULL,
    asset_version INT NULL,
    source_consensus_version_id BIGINT NULL,
    source_narrative_plan_version_id BIGINT NULL,
    source_outline_id BIGINT NULL,
    source_outline_revision INT NULL,
    source_scene_plan_version_id BIGINT NULL,
    source_context_snapshot_id BIGINT NULL,
    source_content_hash CHAR(64) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_asset_source_snapshot (asset_type, asset_id, asset_version),
    KEY idx_asset_source_snapshot_chapter (chapter_id, asset_type, id),
    CONSTRAINT fk_asset_source_snapshot_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_asset_source_snapshot_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chapter_asset_validity_audits (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    chapter_id BIGINT NOT NULL,
    asset_type VARCHAR(32) NOT NULL,
    asset_id BIGINT NOT NULL,
    source_snapshot_id BIGINT NULL,
    event_key VARCHAR(160) NOT NULL,
    validity_status VARCHAR(32) NOT NULL,
    reason_codes_json JSON NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_asset_validity_event (event_key),
    KEY idx_asset_validity_chapter (chapter_id, asset_type, asset_id, id),
    CONSTRAINT fk_asset_validity_snapshot FOREIGN KEY (source_snapshot_id)
        REFERENCES chapter_asset_source_snapshots (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE chapter_outlines
    ADD COLUMN source_snapshot_id BIGINT NULL AFTER migration_reason_codes_json,
    ADD COLUMN validity_status VARCHAR(32) NOT NULL DEFAULT 'needs_review' AFTER source_snapshot_id,
    ADD COLUMN validity_reason_codes_json JSON NULL AFTER validity_status,
    ADD KEY idx_outline_validity (chapter_id, validity_status, revision),
    ADD CONSTRAINT fk_outline_source_snapshot FOREIGN KEY (source_snapshot_id)
        REFERENCES chapter_asset_source_snapshots (id);

ALTER TABLE chapter_plan_versions
    ADD COLUMN source_snapshot_id BIGINT NULL AFTER current_marker,
    ADD COLUMN validity_status VARCHAR(32) NOT NULL DEFAULT 'needs_review' AFTER source_snapshot_id,
    ADD COLUMN validity_reason_codes_json JSON NULL AFTER validity_status,
    ADD KEY idx_chapter_plan_validity (chapter_id, validity_status, plan_no),
    ADD CONSTRAINT fk_chapter_plan_source_snapshot FOREIGN KEY (source_snapshot_id)
        REFERENCES chapter_asset_source_snapshots (id);

ALTER TABLE chapter_generations
    ADD COLUMN source_snapshot_id BIGINT NULL AFTER agent_run_id,
    ADD COLUMN validity_status VARCHAR(32) NOT NULL DEFAULT 'needs_review' AFTER source_snapshot_id,
    ADD COLUMN validity_reason_codes_json JSON NULL AFTER validity_status,
    ADD KEY idx_generation_validity (chapter_id, validity_status, id),
    ADD CONSTRAINT fk_generation_source_snapshot FOREIGN KEY (source_snapshot_id)
        REFERENCES chapter_asset_source_snapshots (id);

UPDATE chapter_outlines SET validity_status = 'needs_review',
    validity_reason_codes_json = JSON_ARRAY('legacy_source_incomplete');
UPDATE chapter_plan_versions SET validity_status = 'needs_review',
    validity_reason_codes_json = JSON_ARRAY('legacy_source_incomplete');
UPDATE chapter_generations SET validity_status = 'needs_review',
    validity_reason_codes_json = JSON_ARRAY('legacy_source_incomplete');
