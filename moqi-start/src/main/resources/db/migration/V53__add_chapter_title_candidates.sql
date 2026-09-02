ALTER TABLE chapters
    MODIFY COLUMN title VARCHAR(255) NULL COMMENT '章节正式标题；NULL 表示尚未命名';

CREATE TABLE chapter_title_candidate_batches (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    ai_task_id BIGINT NOT NULL,
    agent_run_id BIGINT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    batch_status VARCHAR(32) NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    source_object_id VARCHAR(64) NOT NULL,
    source_candidate_id BIGINT NULL,
    source_version INT NOT NULL,
    source_content_hash CHAR(64) NOT NULL,
    source_content_snapshot LONGTEXT NOT NULL,
    prompt_content LONGTEXT NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    prompt_template_version VARCHAR(64) NOT NULL,
    current_attempt INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(500) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_title_batch_idempotency (chapter_id, idempotency_key),
    KEY idx_title_batch_chapter_source (chapter_id, source_object_id, id),
    KEY idx_title_batch_task (ai_task_id),
    CONSTRAINT fk_title_batch_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_title_batch_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_title_batch_task FOREIGN KEY (ai_task_id) REFERENCES ai_tasks (id),
    CONSTRAINT fk_title_batch_run FOREIGN KEY (agent_run_id) REFERENCES agent_runs (id),
    CONSTRAINT fk_title_batch_source_candidate FOREIGN KEY (source_candidate_id)
        REFERENCES chapter_prose_candidates (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chapter_title_candidates (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    candidate_order INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    adopted_title VARCHAR(200) NULL,
    adoption_idempotency_key VARCHAR(128) NULL,
    adopted_chapter_version INT NULL,
    adopted_at DATETIME NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_title_candidate_order (batch_id, candidate_order),
    UNIQUE KEY uk_title_candidate_adoption_key (adoption_idempotency_key),
    CONSTRAINT fk_title_candidate_batch FOREIGN KEY (batch_id)
        REFERENCES chapter_title_candidate_batches (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
