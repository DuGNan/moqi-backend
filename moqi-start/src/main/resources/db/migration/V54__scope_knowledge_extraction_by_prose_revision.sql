ALTER TABLE story_knowledge_extraction_batches
    ADD KEY idx_knowledge_extraction_generation (generation_id);

ALTER TABLE story_knowledge_extraction_batches
    DROP INDEX uk_knowledge_extraction_generation_version,
    ADD COLUMN source_scope_key VARCHAR(160)
        GENERATED ALWAYS AS (
            CASE
                WHEN source_prose_revision_id IS NULL THEN CONCAT('generation:', generation_id)
                ELSE CONCAT(
                    'revision:', source_prose_revision_id,
                    ':release:', COALESCE(CAST(source_story_release_id AS CHAR), 'none')
                )
            END
        ) STORED AFTER source_story_release_id,
    ADD UNIQUE KEY uk_knowledge_extraction_source_version (source_scope_key, extractor_version);
