ALTER TABLE chapter_prose_revisions
    ADD COLUMN quality_generation_id BIGINT NULL COMMENT '修订正文独立质量评价快照，保留原始来源不变',
    ADD UNIQUE KEY uk_revision_quality_generation (quality_generation_id),
    ADD CONSTRAINT fk_revision_quality_generation FOREIGN KEY (quality_generation_id) REFERENCES chapter_generations (id);
