ALTER TABLE chapter_generations
    ADD COLUMN content_assembly_mode VARCHAR(32) NULL AFTER generated_content,
    ADD COLUMN cohesion_status VARCHAR(32) NULL AFTER content_assembly_mode,
    ADD COLUMN cohesion_model_call_id BIGINT NULL AFTER cohesion_status,
    ADD COLUMN cohesion_template_version VARCHAR(64) NULL AFTER cohesion_model_call_id,
    ADD KEY idx_chapter_generations_cohesion_status (cohesion_status, id),
    ADD KEY idx_chapter_generations_cohesion_call (cohesion_model_call_id);
