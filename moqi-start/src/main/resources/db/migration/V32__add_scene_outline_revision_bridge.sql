ALTER TABLE chapter_plan_versions
    MODIFY COLUMN ai_task_id BIGINT NULL,
    ADD COLUMN source_scene_plan_id BIGINT NULL AFTER source_type,
    ADD COLUMN source_scene_plan_version INT NULL AFTER source_scene_plan_id,
    ADD COLUMN revision_idempotency_key VARCHAR(128) NULL AFTER source_scene_plan_version,
    ADD UNIQUE KEY uk_chapter_plan_revision_idempotency (chapter_id, revision_idempotency_key),
    ADD KEY idx_chapter_plan_source_scene (source_scene_plan_id),
    ADD CONSTRAINT fk_chapter_plan_source_scene FOREIGN KEY (source_scene_plan_id)
        REFERENCES chapter_plan_versions (id);

ALTER TABLE chapter_outline_candidates
    ADD COLUMN source_scene_plan_id BIGINT NULL AFTER idempotency_key,
    ADD COLUMN source_scene_plan_version INT NULL AFTER source_scene_plan_id,
    ADD COLUMN source_consistency_report_id BIGINT NULL AFTER source_scene_plan_version,
    ADD COLUMN scene_diff_json JSON NULL AFTER source_consistency_report_id,
    ADD KEY idx_outline_candidate_source_scene (source_scene_plan_id),
    ADD KEY idx_outline_candidate_source_report (source_consistency_report_id),
    ADD CONSTRAINT fk_outline_candidate_source_scene FOREIGN KEY (source_scene_plan_id)
        REFERENCES chapter_plan_versions (id),
    ADD CONSTRAINT fk_outline_candidate_source_report FOREIGN KEY (source_consistency_report_id)
        REFERENCES scene_plan_consistency_reports (id);
