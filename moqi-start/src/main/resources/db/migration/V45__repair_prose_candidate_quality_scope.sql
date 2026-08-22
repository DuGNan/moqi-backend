UPDATE chapter_prose_candidates candidate
SET candidate.quality_request_status = 'unavailable'
WHERE candidate.deleted = 0
  AND candidate.quality_request_status = 'requested'
  AND candidate.quality_generation_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM chapter_generation_evaluation_reports report
      WHERE report.generation_id = candidate.quality_generation_id
        AND report.generation_scene_id IS NULL
        AND report.deleted = 0
  );
