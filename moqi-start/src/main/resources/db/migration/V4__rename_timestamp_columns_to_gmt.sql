ALTER TABLE works
    RENAME COLUMN created_at TO gmt_create,
    RENAME COLUMN updated_at TO gmt_modified,
    RENAME INDEX idx_works_status_updated_at TO idx_works_status_gmt_modified;

ALTER TABLE chapters
    RENAME COLUMN created_at TO gmt_create,
    RENAME COLUMN updated_at TO gmt_modified;

ALTER TABLE chapter_conversations
    RENAME COLUMN created_at TO gmt_create,
    RENAME COLUMN updated_at TO gmt_modified,
    RENAME INDEX idx_chapter_conversations_work_updated_at TO idx_chapter_conversations_work_gmt_modified;

ALTER TABLE chapter_conversation_messages
    RENAME COLUMN created_at TO gmt_create,
    RENAME COLUMN updated_at TO gmt_modified,
    RENAME INDEX idx_ccm_conversation_created_at TO idx_ccm_conversation_gmt_create,
    RENAME INDEX idx_ccm_chapter_created_at TO idx_ccm_chapter_gmt_create;

ALTER TABLE chapter_briefs
    RENAME COLUMN created_at TO gmt_create,
    RENAME COLUMN updated_at TO gmt_modified,
    RENAME INDEX idx_chapter_briefs_work_updated_at TO idx_chapter_briefs_work_gmt_modified;

ALTER TABLE chapter_generations
    RENAME COLUMN created_at TO gmt_create,
    RENAME COLUMN updated_at TO gmt_modified,
    RENAME INDEX idx_chapter_generations_work_updated_at TO idx_chapter_generations_work_gmt_modified;
