ALTER TABLE chapter_generations
    ADD COLUMN chapter_plan_version_id BIGINT NULL AFTER outline_revision,
    ADD COLUMN base_generation_id BIGINT NULL AFTER chapter_plan_version_id,
    ADD COLUMN agent_run_id BIGINT NULL AFTER ai_task_id,
    ADD COLUMN selection_mode VARCHAR(32) NULL AFTER generation_mode,
    ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER selection_mode,
    ADD COLUMN execution_config_json JSON NULL AFTER basis_snapshot_json,
    ADD KEY idx_chapter_generations_chapter_plan_id (chapter_plan_version_id),
    ADD KEY idx_chapter_generations_base_generation_id (base_generation_id),
    ADD KEY idx_chapter_generations_agent_run_id (agent_run_id),
    ADD UNIQUE KEY uk_chapter_generations_chapter_idempotency (chapter_id, idempotency_key),
    ADD CONSTRAINT fk_chapter_generations_chapter_plan_id
        FOREIGN KEY (chapter_plan_version_id) REFERENCES chapter_plan_versions (id),
    ADD CONSTRAINT fk_chapter_generations_base_generation_id
        FOREIGN KEY (base_generation_id) REFERENCES chapter_generations (id),
    ADD CONSTRAINT fk_chapter_generations_agent_run_id
        FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id);

CREATE TABLE chapter_generation_scenes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    generation_id BIGINT NOT NULL,
    scene_plan_version_id BIGINT NOT NULL,
    scene_key VARCHAR(128) NOT NULL,
    sequence_no INT NOT NULL,
    context_snapshot_id BIGINT NULL,
    prompt_template_version VARCHAR(64) NOT NULL,
    scene_status VARCHAR(32) NOT NULL,
    generated_content LONGTEXT NULL,
    content_hash CHAR(64) NULL,
    word_count INT NOT NULL DEFAULT 0,
    source_scene_draft_id BIGINT NULL,
    model_call_id BIGINT NULL,
    finish_reason VARCHAR(64) NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    total_tokens INT NULL,
    elapsed_millis BIGINT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_generation_scene_plan (generation_id, scene_plan_version_id),
    UNIQUE KEY uk_generation_scene_sequence (generation_id, sequence_no),
    KEY idx_generation_scenes_generation_status (generation_id, scene_status, sequence_no),
    KEY idx_generation_scenes_context_snapshot (context_snapshot_id),
    KEY idx_generation_scenes_source (source_scene_draft_id),
    CONSTRAINT fk_generation_scenes_generation_id
        FOREIGN KEY (generation_id) REFERENCES chapter_generations (id),
    CONSTRAINT fk_generation_scenes_plan_version_id
        FOREIGN KEY (scene_plan_version_id) REFERENCES scene_plan_versions (id),
    CONSTRAINT fk_generation_scenes_context_snapshot_id
        FOREIGN KEY (context_snapshot_id) REFERENCES story_context_snapshots (id),
    CONSTRAINT fk_generation_scenes_source_id
        FOREIGN KEY (source_scene_draft_id) REFERENCES chapter_generation_scenes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE llm_model_calls (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    generation_scene_id BIGINT NULL,
    agent_run_id BIGINT NULL,
    agent_step_id BIGINT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    config_version INT NOT NULL,
    credential_version INT NOT NULL,
    prompt_template_version VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    call_status VARCHAR(32) NOT NULL,
    finish_reason VARCHAR(64) NULL,
    provider_request_id VARCHAR(255) NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    total_tokens INT NULL,
    error_category VARCHAR(64) NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(500) NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    elapsed_millis BIGINT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_llm_model_calls_scene_status (generation_scene_id, call_status, id),
    KEY idx_llm_model_calls_run_step (agent_run_id, agent_step_id, id),
    KEY idx_llm_model_calls_provider_request (provider_request_id),
    CONSTRAINT fk_llm_model_calls_generation_scene_id
        FOREIGN KEY (generation_scene_id) REFERENCES chapter_generation_scenes (id),
    CONSTRAINT fk_llm_model_calls_agent_run_id
        FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id),
    CONSTRAINT fk_llm_model_calls_agent_step_id
        FOREIGN KEY (agent_step_id) REFERENCES agent_run_steps (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE chapter_generation_scenes
    ADD KEY idx_generation_scenes_model_call (model_call_id),
    ADD CONSTRAINT fk_generation_scenes_model_call_id
        FOREIGN KEY (model_call_id) REFERENCES llm_model_calls (id);
