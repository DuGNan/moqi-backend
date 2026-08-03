ALTER TABLE llm_model_calls
    ADD COLUMN user_id VARCHAR(64) NULL AFTER conversation_id,
    ADD COLUMN work_id BIGINT NULL AFTER user_id,
    ADD COLUMN chapter_id BIGINT NULL AFTER work_id,
    ADD COLUMN workflow_type VARCHAR(64) NULL AFTER chapter_id,
    ADD COLUMN operation_type VARCHAR(64) NULL AFTER workflow_type,
    ADD COLUMN logical_call_id VARCHAR(128) NULL AFTER operation_type,
    ADD COLUMN attempt_no INT NOT NULL DEFAULT 1 AFTER logical_call_id,
    ADD COLUMN price_version_id BIGINT NULL AFTER total_tokens,
    ADD COLUMN estimated_cost DECIMAL(20, 8) NULL AFTER price_version_id,
    ADD COLUMN currency VARCHAR(16) NULL AFTER estimated_cost,
    ADD COLUMN cost_status VARCHAR(32) NOT NULL DEFAULT 'unpriced' AFTER currency,
    ADD UNIQUE KEY uk_llm_model_calls_logical_attempt (logical_call_id, attempt_no),
    ADD KEY idx_llm_model_calls_user_time (user_id, started_at, id),
    ADD KEY idx_llm_model_calls_work_time (work_id, started_at, id),
    ADD KEY idx_llm_model_calls_model_time (provider, model, started_at, id),
    ADD KEY idx_llm_model_calls_workflow_time (workflow_type, started_at, id),
    ADD CONSTRAINT fk_llm_model_calls_work_id
        FOREIGN KEY (work_id) REFERENCES works (id),
    ADD CONSTRAINT fk_llm_model_calls_chapter_id
        FOREIGN KEY (chapter_id) REFERENCES chapters (id);

CREATE TABLE llm_model_prices (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    input_cache_hit_price_per_million DECIMAL(20, 8) NOT NULL,
    input_cache_miss_price_per_million DECIMAL(20, 8) NOT NULL,
    output_price_per_million DECIMAL(20, 8) NOT NULL,
    effective_from DATETIME NOT NULL,
    effective_to DATETIME NULL,
    source_reference VARCHAR(500) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_llm_model_prices_version (provider, model, currency, effective_from, deleted),
    KEY idx_llm_model_prices_lookup (provider, model, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO llm_model_prices (
    provider,
    model,
    currency,
    input_cache_hit_price_per_million,
    input_cache_miss_price_per_million,
    output_price_per_million,
    effective_from,
    source_reference,
    deleted,
    version
) VALUES
    ('deepseek', 'deepseek-v4-flash', 'USD', 0.00280000, 0.14000000, 0.28000000,
        '2026-08-04 00:00:00', 'https://api-docs.deepseek.com/quick_start/pricing/', 0, 0),
    ('deepseek', 'deepseek-v4-pro', 'USD', 0.00362500, 0.43500000, 0.87000000,
        '2026-08-04 00:00:00', 'https://api-docs.deepseek.com/quick_start/pricing/', 0, 0);

ALTER TABLE llm_model_calls
    ADD KEY idx_llm_model_calls_price_version (price_version_id),
    ADD CONSTRAINT fk_llm_model_calls_price_version_id
        FOREIGN KEY (price_version_id) REFERENCES llm_model_prices (id);
