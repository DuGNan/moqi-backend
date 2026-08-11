ALTER TABLE scene_plan_versions
    ADD COLUMN content_schema_version INT NOT NULL DEFAULT 1 AFTER sequence_no;
