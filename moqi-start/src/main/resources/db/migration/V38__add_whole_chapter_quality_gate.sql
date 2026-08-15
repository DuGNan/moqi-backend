ALTER TABLE chapter_generation_evaluation_reports
    ADD COLUMN content_hash CHAR(64) NULL AFTER evaluator_version,
    ADD COLUMN brief_fingerprint CHAR(64) NULL AFTER content_hash,
    ADD COLUMN source_fingerprint CHAR(64) NULL AFTER brief_fingerprint,
    ADD COLUMN model_call_id BIGINT NULL AFTER source_fingerprint,
    ADD COLUMN error_code VARCHAR(64) NULL AFTER model_call_id,
    ADD COLUMN error_message VARCHAR(500) NULL AFTER error_code,
    ADD KEY idx_generation_evaluation_content (generation_id, content_hash, ruleset_version, evaluator_version),
    ADD CONSTRAINT fk_generation_evaluation_model_call
        FOREIGN KEY (model_call_id) REFERENCES llm_model_calls (id);

UPDATE chapter_generation_evaluation_reports
SET content_hash = input_fingerprint,
    source_fingerprint = input_fingerprint
WHERE content_hash IS NULL;
