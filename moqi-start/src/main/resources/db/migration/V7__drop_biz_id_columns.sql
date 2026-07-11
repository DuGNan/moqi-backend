ALTER TABLE works
    DROP INDEX uk_works_biz_id,
    DROP COLUMN biz_id;

ALTER TABLE chapters
    DROP INDEX uk_chapters_biz_id,
    DROP COLUMN biz_id;

ALTER TABLE chapter_conversations
    DROP INDEX uk_chapter_conversations_biz_id,
    DROP COLUMN biz_id;

ALTER TABLE chapter_conversation_messages
    DROP INDEX uk_chapter_conversation_messages_biz_id,
    DROP COLUMN biz_id;

ALTER TABLE chapter_briefs
    DROP INDEX uk_chapter_briefs_biz_id,
    DROP COLUMN biz_id;

ALTER TABLE chapter_generations
    DROP INDEX uk_chapter_generations_biz_id,
    DROP COLUMN biz_id;

ALTER TABLE chapter_outlines
    DROP INDEX uk_chapter_outlines_biz_id,
    DROP COLUMN biz_id;

ALTER TABLE user_configs
    DROP INDEX uk_user_configs_biz_id,
    DROP COLUMN biz_id;

ALTER TABLE ai_tasks
    DROP INDEX uk_ai_tasks_biz_id,
    DROP COLUMN biz_id;
