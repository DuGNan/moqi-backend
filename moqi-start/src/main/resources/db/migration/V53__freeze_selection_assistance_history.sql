ALTER TABLE chapter_selection_assistance
    ADD COLUMN conversation_history_json LONGTEXT NULL COMMENT '创建选区协助时冻结的正文对象会话历史 JSON'
        AFTER assistant_message_id;
