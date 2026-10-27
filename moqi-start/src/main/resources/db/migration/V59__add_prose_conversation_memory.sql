ALTER TABLE chapter_conversation_messages
    ADD COLUMN prose_reference_json JSON NULL COMMENT '仅属于这条消息的正文引用';

CREATE TABLE prose_conversation_memories (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL COMMENT '正文对象会话',
    through_message_id BIGINT NOT NULL DEFAULT 0 COMMENT '已压缩到的消息位置',
    summary_json JSON NOT NULL COMMENT '分类摘要及原始消息来源',
    source_fingerprint CHAR(64) NOT NULL COMMENT '摘要输入指纹',
    source_snapshot_json JSON NOT NULL COMMENT '本次摘要实际模型消息及来源关系',
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prose_memory_conversation (conversation_id),
    CONSTRAINT fk_prose_memory_conversation FOREIGN KEY (conversation_id) REFERENCES chapter_conversations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正文会话增量摘要；不代表作者确认的作品事实';
