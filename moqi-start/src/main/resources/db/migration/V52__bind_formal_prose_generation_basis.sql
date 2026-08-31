ALTER TABLE chapters
    ADD COLUMN formal_source_generation_id BIGINT NULL
        COMMENT '正式正文当前可追溯的生成来源，用于读取冻结生成依据'
        AFTER current_prose_revision_id,
    ADD KEY idx_chapters_formal_source_generation (formal_source_generation_id),
    ADD CONSTRAINT fk_chapters_formal_source_generation
        FOREIGN KEY (formal_source_generation_id) REFERENCES chapter_generations (id);

UPDATE chapters chapter
JOIN chapter_prose_revisions revision
  ON revision.id = chapter.current_prose_revision_id
 AND revision.chapter_id = chapter.id
 AND revision.deleted = 0
SET chapter.formal_source_generation_id = revision.source_generation_id
WHERE chapter.formal_source_generation_id IS NULL
  AND revision.source_generation_id IS NOT NULL;

UPDATE chapters chapter
SET chapter.formal_source_generation_id = (
    SELECT candidate.source_generation_id
    FROM chapter_prose_candidate_adoptions adoption
    JOIN chapter_prose_candidates candidate
      ON candidate.id = adoption.candidate_id
     AND candidate.chapter_id = adoption.chapter_id
     AND candidate.deleted = 0
    WHERE adoption.chapter_id = chapter.id
      AND adoption.adoption_mode = 'direct_formal'
      AND adoption.adoption_status = 'completed'
      AND adoption.deleted = 0
      AND candidate.source_generation_id IS NOT NULL
    ORDER BY adoption.id DESC
    LIMIT 1
)
WHERE chapter.formal_source_generation_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM chapter_prose_candidate_adoptions adoption
    JOIN chapter_prose_candidates candidate
      ON candidate.id = adoption.candidate_id
     AND candidate.chapter_id = adoption.chapter_id
     AND candidate.deleted = 0
    WHERE adoption.chapter_id = chapter.id
      AND adoption.adoption_mode = 'direct_formal'
      AND adoption.adoption_status = 'completed'
      AND adoption.deleted = 0
      AND candidate.source_generation_id IS NOT NULL
  );

UPDATE chapters chapter
SET chapter.formal_source_generation_id = (
    SELECT generation.id
    FROM chapter_generations generation
    WHERE generation.chapter_id = chapter.id
      AND generation.generation_status = 'accepted'
      AND generation.deleted = 0
    ORDER BY generation.gmt_modified DESC, generation.id DESC
    LIMIT 1
)
WHERE chapter.formal_source_generation_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM chapter_generations generation
    WHERE generation.chapter_id = chapter.id
      AND generation.generation_status = 'accepted'
      AND generation.deleted = 0
  );
