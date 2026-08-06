UPDATE chapter_generations preview
JOIN (
    SELECT obsolete_generation_id
    FROM (
        SELECT candidate.id AS obsolete_generation_id
        FROM chapter_generations candidate
        JOIN chapter_generations accepted
          ON accepted.chapter_id = candidate.chapter_id
         AND accepted.generation_status = 'accepted'
         AND accepted.deleted = 0
         AND (accepted.gmt_create > candidate.gmt_create
              OR (accepted.gmt_create = candidate.gmt_create AND accepted.id > candidate.id))
        WHERE candidate.generation_status = 'preview'
          AND candidate.deleted = 0
        GROUP BY candidate.id
    ) AS obsolete_generations
) AS resolved_obsolete ON resolved_obsolete.obsolete_generation_id = preview.id
SET preview.generation_status = 'superseded',
    preview.version = preview.version + 1,
    preview.gmt_modified = CURRENT_TIMESTAMP;
