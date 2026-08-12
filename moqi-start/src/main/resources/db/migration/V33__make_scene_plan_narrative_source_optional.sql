ALTER TABLE chapter_plan_versions
    MODIFY COLUMN narrative_plan_id BIGINT NULL,
    MODIFY COLUMN narrative_plan_no INT NULL;
