ALTER TABLE chapter_outlines
    ADD COLUMN content_schema_version INT NOT NULL DEFAULT 1 AFTER outline_content,
    ADD COLUMN migration_review_status VARCHAR(32) NOT NULL DEFAULT 'review_required' AFTER content_schema_version,
    ADD COLUMN migration_reason_codes_json JSON NULL AFTER migration_review_status;

ALTER TABLE chapter_outline_candidates
    ADD COLUMN content_schema_version INT NOT NULL DEFAULT 1 AFTER candidate_content,
    ADD COLUMN migration_review_status VARCHAR(32) NOT NULL DEFAULT 'review_required' AFTER content_schema_version,
    ADD COLUMN migration_reason_codes_json JSON NULL AFTER migration_review_status;

ALTER TABLE chapter_plan_versions
    ADD COLUMN outline_content_schema_version INT NOT NULL DEFAULT 1 AFTER outline_revision,
    ADD COLUMN outline_migration_review_status VARCHAR(32) NOT NULL DEFAULT 'review_required'
        AFTER outline_content_schema_version;

UPDATE chapter_outlines
SET content_schema_version = 1,
    migration_review_status = 'review_required',
    migration_reason_codes_json = JSON_ARRAY('legacy_outline_v1');

UPDATE chapter_outline_candidates
SET content_schema_version = 1,
    migration_review_status = 'review_required',
    migration_reason_codes_json = JSON_ARRAY('legacy_outline_v1');

UPDATE chapter_plan_versions
SET outline_content_schema_version = 1,
    outline_migration_review_status = 'review_required';
