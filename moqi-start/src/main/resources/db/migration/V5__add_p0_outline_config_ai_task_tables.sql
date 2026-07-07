CREATE TABLE IF NOT EXISTS chapter_outlines (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_id VARCHAR(64) NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    outline_status VARCHAR(32) NOT NULL DEFAULT 'draft',
    outline_content JSON NOT NULL,
    revision INT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chapter_outlines_biz_id (biz_id),
    UNIQUE KEY uk_chapter_outlines_chapter_id (chapter_id),
    KEY idx_chapter_outlines_work_gmt_modified (work_id, gmt_modified),
    KEY idx_chapter_outlines_status_gmt_modified (outline_status, gmt_modified),
    CONSTRAINT fk_chapter_outlines_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_chapter_outlines_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_configs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    config_key VARCHAR(128) NOT NULL,
    config_value JSON NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_configs_biz_id (biz_id),
    UNIQUE KEY uk_user_configs_user_key_deleted (user_id, config_key, deleted),
    KEY idx_user_configs_user_gmt_modified (user_id, gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ai_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_id VARCHAR(64) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    task_status VARCHAR(32) NOT NULL DEFAULT 'queued',
    work_id BIGINT NULL,
    chapter_id BIGINT NULL,
    result_message_id BIGINT NULL,
    result_generation_id BIGINT NULL,
    result_suggestion_id BIGINT NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(500) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_tasks_biz_id (biz_id),
    KEY idx_ai_tasks_chapter_status_gmt_create (chapter_id, task_status, gmt_create),
    KEY idx_ai_tasks_work_type_gmt_create (work_id, task_type, gmt_create),
    KEY idx_ai_tasks_status_gmt_create (task_status, gmt_create),
    KEY idx_ai_tasks_result_message_id (result_message_id),
    KEY idx_ai_tasks_result_generation_id (result_generation_id),
    CONSTRAINT fk_ai_tasks_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_ai_tasks_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_ai_tasks_result_message_id FOREIGN KEY (result_message_id) REFERENCES chapter_conversation_messages (id),
    CONSTRAINT fk_ai_tasks_result_generation_id FOREIGN KEY (result_generation_id) REFERENCES chapter_generations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE chapter_conversations
    ADD COLUMN conversation_type VARCHAR(32) NOT NULL DEFAULT 'chapter_co_creation' AFTER chapter_id;

ALTER TABLE chapter_conversation_messages
    ADD COLUMN ai_task_id BIGINT NULL AFTER content,
    ADD KEY idx_ccm_ai_task_id (ai_task_id),
    ADD CONSTRAINT fk_ccm_ai_task_id FOREIGN KEY (ai_task_id) REFERENCES ai_tasks (id);

ALTER TABLE chapter_generations
    MODIFY COLUMN generated_content LONGTEXT NULL,
    ADD COLUMN outline_id BIGINT NULL AFTER brief_id,
    ADD COLUMN outline_revision INT NULL AFTER outline_id,
    ADD COLUMN generation_mode VARCHAR(32) NULL AFTER generation_status,
    ADD COLUMN length_preset VARCHAR(32) NULL AFTER generation_mode,
    ADD COLUMN custom_word_count INT NULL AFTER length_preset,
    ADD COLUMN basis_snapshot_json JSON NULL AFTER custom_word_count,
    ADD COLUMN word_count INT NOT NULL DEFAULT 0 AFTER generated_content,
    ADD COLUMN ai_task_id BIGINT NULL AFTER word_count,
    ADD KEY idx_chapter_generations_outline_id (outline_id),
    ADD KEY idx_chapter_generations_ai_task_id (ai_task_id),
    ADD CONSTRAINT fk_chapter_generations_outline_id FOREIGN KEY (outline_id) REFERENCES chapter_outlines (id),
    ADD CONSTRAINT fk_chapter_generations_ai_task_id FOREIGN KEY (ai_task_id) REFERENCES ai_tasks (id);
