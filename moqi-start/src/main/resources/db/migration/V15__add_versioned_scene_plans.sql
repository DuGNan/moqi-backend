CREATE TABLE work_narrative_plan_versions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    plan_no INT NOT NULL,
    plan_status VARCHAR(32) NOT NULL,
    content_json JSON NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    published_by VARCHAR(64) NULL,
    current_marker TINYINT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_work_narrative_plan_no (work_id, plan_no),
    UNIQUE KEY uk_work_narrative_plan_current (work_id, current_marker),
    CONSTRAINT fk_work_narrative_plan_work_id FOREIGN KEY (work_id) REFERENCES works (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chapter_plan_versions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    plan_no INT NOT NULL,
    narrative_plan_id BIGINT NOT NULL,
    narrative_plan_no INT NOT NULL,
    outline_id BIGINT NOT NULL,
    outline_revision INT NOT NULL,
    agent_run_id BIGINT NULL,
    ai_task_id BIGINT NOT NULL,
    plan_status VARCHAR(32) NOT NULL,
    content_json JSON NULL,
    source_type VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    published_by VARCHAR(64) NULL,
    current_marker TINYINT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chapter_plan_no (chapter_id, plan_no),
    UNIQUE KEY uk_chapter_plan_current (chapter_id, current_marker),
    KEY idx_chapter_plan_status (chapter_id, plan_status, id),
    CONSTRAINT fk_chapter_plan_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_chapter_plan_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_chapter_plan_narrative_id FOREIGN KEY (narrative_plan_id) REFERENCES work_narrative_plan_versions (id),
    CONSTRAINT fk_chapter_plan_outline_id FOREIGN KEY (outline_id) REFERENCES chapter_outlines (id),
    CONSTRAINT fk_chapter_plan_ai_task_id FOREIGN KEY (ai_task_id) REFERENCES ai_tasks (id),
    CONSTRAINT fk_chapter_plan_agent_run_id FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scene_plan_versions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    chapter_plan_version_id BIGINT NOT NULL,
    scene_key VARCHAR(128) NOT NULL,
    sequence_no INT NOT NULL,
    content_json JSON NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_scene_plan_key (chapter_plan_version_id, scene_key),
    UNIQUE KEY uk_scene_plan_sequence (chapter_plan_version_id, sequence_no),
    CONSTRAINT fk_scene_plan_chapter_plan_id FOREIGN KEY (chapter_plan_version_id) REFERENCES chapter_plan_versions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE ai_tasks
    ADD COLUMN result_scene_plan_version_id BIGINT NULL AFTER result_outline_candidate_id,
    ADD KEY idx_ai_tasks_result_scene_plan_version_id (result_scene_plan_version_id),
    ADD CONSTRAINT fk_ai_tasks_result_scene_plan_version_id
        FOREIGN KEY (result_scene_plan_version_id) REFERENCES chapter_plan_versions (id);
