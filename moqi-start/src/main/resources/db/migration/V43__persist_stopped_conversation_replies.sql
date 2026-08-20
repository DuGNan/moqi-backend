ALTER TABLE chapter_conversation_messages
    ADD COLUMN generation_status VARCHAR(32) NULL AFTER content;

UPDATE chapter_conversation_messages
SET generation_status = 'completed'
WHERE message_role = 'assistant'
  AND ai_task_id IS NOT NULL
  AND deleted = 0;

ALTER TABLE chapter_conversation_messages
    MODIFY COLUMN generation_status VARCHAR(32) NULL
        COMMENT '生成状态：completed完整生成，stopped用户停止后保留的不完整内容';

ALTER TABLE ai_tasks
    ADD COLUMN retry_of_task_id BIGINT NULL AFTER task_status,
    ADD UNIQUE KEY uk_ai_tasks_retry_of_task_id (retry_of_task_id),
    ADD CONSTRAINT fk_ai_tasks_retry_of_task_id
        FOREIGN KEY (retry_of_task_id) REFERENCES ai_tasks (id);
