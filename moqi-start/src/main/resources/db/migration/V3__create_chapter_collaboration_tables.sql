CREATE TABLE IF NOT EXISTS chapter_conversations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_id VARCHAR(64) NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    conversation_status VARCHAR(32) NOT NULL DEFAULT 'active',
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chapter_conversations_biz_id (biz_id),
    KEY idx_chapter_conversations_chapter_status (chapter_id, conversation_status),
    KEY idx_chapter_conversations_work_updated_at (work_id, updated_at),
    CONSTRAINT fk_chapter_conversations_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_chapter_conversations_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS chapter_conversation_messages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_id VARCHAR(64) NOT NULL,
    conversation_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    message_role VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chapter_conversation_messages_biz_id (biz_id),
    KEY idx_ccm_conversation_created_at (conversation_id, created_at),
    KEY idx_ccm_chapter_created_at (chapter_id, created_at),
    CONSTRAINT fk_ccm_conversation_id FOREIGN KEY (conversation_id) REFERENCES chapter_conversations (id),
    CONSTRAINT fk_ccm_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS chapter_briefs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_id VARCHAR(64) NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    brief_status VARCHAR(32) NOT NULL DEFAULT 'draft',
    brief_content LONGTEXT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chapter_briefs_biz_id (biz_id),
    KEY idx_chapter_briefs_chapter_status (chapter_id, brief_status),
    KEY idx_chapter_briefs_work_updated_at (work_id, updated_at),
    CONSTRAINT fk_chapter_briefs_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_chapter_briefs_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS chapter_generations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_id VARCHAR(64) NOT NULL,
    work_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    brief_id BIGINT NULL,
    generation_status VARCHAR(32) NOT NULL DEFAULT 'draft',
    generated_content LONGTEXT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chapter_generations_biz_id (biz_id),
    KEY idx_chapter_generations_chapter_status (chapter_id, generation_status),
    KEY idx_chapter_generations_work_updated_at (work_id, updated_at),
    KEY idx_chapter_generations_brief_id (brief_id),
    CONSTRAINT fk_chapter_generations_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_chapter_generations_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_chapter_generations_brief_id FOREIGN KEY (brief_id) REFERENCES chapter_briefs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
