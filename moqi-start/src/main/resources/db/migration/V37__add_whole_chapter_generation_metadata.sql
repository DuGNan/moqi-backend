ALTER TABLE chapter_generations
    ADD COLUMN generation_model_call_id BIGINT NULL AFTER cohesion_template_version,
    ADD COLUMN generation_template_version VARCHAR(64) NULL AFTER generation_model_call_id,
    ADD COLUMN generation_finish_reason VARCHAR(64) NULL AFTER generation_template_version,
    ADD KEY idx_chapter_generations_model_call (generation_model_call_id);
