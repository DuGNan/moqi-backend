CREATE TABLE chapter_prose_candidates (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    root_candidate_id BIGINT NULL,
    parent_candidate_id BIGINT NULL,
    source_kind VARCHAR(32) NOT NULL,
    source_generation_id BIGINT NULL,
    source_bounded_revision_id BIGINT NULL,
    quality_generation_id BIGINT NULL,
    quality_request_status VARCHAR(32) NOT NULL,
    candidate_status VARCHAR(32) NOT NULL,
    adoption_status VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    word_count INT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prose_candidate_source_generation (source_generation_id),
    KEY idx_prose_candidate_chapter_status (chapter_id, candidate_status, id),
    KEY idx_prose_candidate_root (root_candidate_id, id),
    KEY idx_prose_candidate_quality_generation (quality_generation_id),
    CONSTRAINT fk_prose_candidate_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_prose_candidate_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_prose_candidate_parent FOREIGN KEY (parent_candidate_id)
        REFERENCES chapter_prose_candidates (id),
    CONSTRAINT fk_prose_candidate_source_generation FOREIGN KEY (source_generation_id)
        REFERENCES chapter_generations (id),
    CONSTRAINT fk_prose_candidate_bounded_revision FOREIGN KEY (source_bounded_revision_id)
        REFERENCES bounded_chapter_revisions (id),
    CONSTRAINT fk_prose_candidate_quality_generation FOREIGN KEY (quality_generation_id)
        REFERENCES chapter_generations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chapter_prose_workspace_selections (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    selected_object_kind VARCHAR(16) NOT NULL,
    selected_object_id VARCHAR(64) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prose_workspace_selection_chapter (chapter_id),
    CONSTRAINT fk_prose_workspace_selection_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_prose_workspace_selection_chapter FOREIGN KEY (chapter_id) REFERENCES chapters (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO chapter_prose_candidates (
    work_id, chapter_id, source_kind, source_generation_id, source_bounded_revision_id,
    quality_generation_id, quality_request_status, candidate_status, adoption_status, content, content_hash,
    word_count, deleted, version, gmt_create, gmt_modified
)
SELECT generation.work_id,
       generation.chapter_id,
       CASE WHEN bounded.id IS NULL THEN 'generation' ELSE 'bounded_revision' END,
       generation.id,
       bounded.id,
       generation.id,
       CASE
           WHEN EXISTS (
               SELECT 1 FROM chapter_generation_evaluation_reports report
               WHERE report.generation_id = generation.id AND report.deleted = 0
           ) THEN 'requested'
           ELSE 'unavailable'
       END,
       CASE WHEN generation.generation_status IN ('rejected', 'superseded') THEN 'history' ELSE 'active' END,
       CASE
           WHEN generation.generation_status = 'accepted' THEN 'adopted'
           WHEN generation.generation_status = 'superseded' THEN 'replaced'
           ELSE 'unadopted'
       END,
       generation.generated_content,
       SHA2(COALESCE(generation.generated_content, ''), 256),
       CHAR_LENGTH(COALESCE(generation.generated_content, '')),
       0,
       0,
       generation.gmt_create,
       generation.gmt_modified
FROM chapter_generations generation
LEFT JOIN bounded_chapter_revisions bounded
       ON bounded.result_generation_id = generation.id AND bounded.deleted = 0
WHERE generation.generated_content IS NOT NULL
  AND generation.generation_status IN ('preview', 'accepted', 'rejected', 'superseded')
  AND generation.deleted = 0;

UPDATE chapter_prose_candidates candidate
LEFT JOIN chapter_generations generation ON generation.id = candidate.source_generation_id
LEFT JOIN chapter_prose_candidates parent_candidate
       ON parent_candidate.source_generation_id = generation.base_generation_id
SET candidate.parent_candidate_id = parent_candidate.id;

WITH RECURSIVE candidate_tree AS (
    SELECT candidate.id, candidate.id AS root_candidate_id
    FROM chapter_prose_candidates candidate
    WHERE candidate.parent_candidate_id IS NULL
    UNION ALL
    SELECT child.id, parent.root_candidate_id
    FROM chapter_prose_candidates child
    JOIN candidate_tree parent ON parent.id = child.parent_candidate_id
)
UPDATE chapter_prose_candidates candidate
JOIN candidate_tree tree ON tree.id = candidate.id
SET candidate.root_candidate_id = tree.root_candidate_id;

ALTER TABLE chapter_prose_candidates
    ADD CONSTRAINT fk_prose_candidate_root FOREIGN KEY (root_candidate_id)
        REFERENCES chapter_prose_candidates (id);
