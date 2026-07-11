ALTER TABLE chapters
    ADD COLUMN chapter_type VARCHAR(32) NOT NULL DEFAULT 'chapter' COMMENT '章节类型：dedication献词，prologue序幕，chapter正文，epilogue尾声，other其他' AFTER chapter_no,
    ADD KEY idx_chapters_work_type (work_id, chapter_type);

CREATE TABLE IF NOT EXISTS setting_candidates (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    work_id BIGINT NOT NULL COMMENT '所属作品ID',
    chapter_id BIGINT NULL COMMENT '来源章节ID',
    source_type VARCHAR(32) NOT NULL COMMENT '来源类型：chapter_content章节正文，conversation_message会话消息，generation_result生成结果',
    source_id BIGINT NULL COMMENT '来源记录ID',
    source_content_revision INT NULL COMMENT '来源正文修订版本',
    source_start_offset INT NULL COMMENT '来源正文起始偏移',
    source_end_offset INT NULL COMMENT '来源正文结束偏移',
    setting_type VARCHAR(32) NOT NULL COMMENT '设定类型：character人物，place地点，organization组织，rule规则，item物品，other其他',
    name VARCHAR(255) NOT NULL COMMENT '候选设定名称',
    content LONGTEXT NOT NULL COMMENT '候选设定内容',
    candidate_status VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '候选状态：pending待确认，confirmed已确认，ignored已忽略',
    confirmed_setting_id BIGINT NULL COMMENT '确认后关联的正式设定ID',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除：0否，1是',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_setting_candidates_work_status (work_id, candidate_status),
    KEY idx_setting_candidates_chapter_type (chapter_id, setting_type),
    KEY idx_setting_candidates_confirmed_setting_id (confirmed_setting_id),
    CONSTRAINT fk_setting_candidates_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_setting_candidates_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='待确认设定表';

CREATE TABLE IF NOT EXISTS setting_entries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    work_id BIGINT NOT NULL COMMENT '所属作品ID',
    setting_type VARCHAR(32) NOT NULL COMMENT '设定类型：character人物，place地点，organization组织，rule规则，item物品，other其他',
    name VARCHAR(255) NOT NULL COMMENT '设定名称',
    aliases_json JSON NULL COMMENT '别名JSON数组',
    content LONGTEXT NOT NULL COMMENT '正式设定正文',
    attributes_json JSON NULL COMMENT '结构化属性JSON',
    source_chapter_id BIGINT NULL COMMENT '首次来源章节ID',
    source_candidate_id BIGINT NULL COMMENT '来源候选设定ID',
    entry_status VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '设定状态：active有效，deprecated已废弃',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除：0否，1是',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_setting_entries_work_type (work_id, setting_type),
    KEY idx_setting_entries_work_name (work_id, name),
    KEY idx_setting_entries_source_chapter_id (source_chapter_id),
    KEY idx_setting_entries_source_candidate_id (source_candidate_id),
    CONSTRAINT fk_setting_entries_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_setting_entries_source_chapter_id FOREIGN KEY (source_chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_setting_entries_source_candidate_id FOREIGN KEY (source_candidate_id) REFERENCES setting_candidates (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='作品级正式设定表';

ALTER TABLE setting_candidates
    ADD CONSTRAINT fk_setting_candidates_confirmed_setting_id FOREIGN KEY (confirmed_setting_id) REFERENCES setting_entries (id);

CREATE TABLE IF NOT EXISTS foreshadowing_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    work_id BIGINT NOT NULL COMMENT '所属作品ID',
    source_chapter_id BIGINT NOT NULL COMMENT '伏笔来源章节ID',
    title VARCHAR(255) NOT NULL COMMENT '伏笔标题',
    description LONGTEXT NOT NULL COMMENT '伏笔说明',
    source_text LONGTEXT NULL COMMENT '来源原文片段',
    source_start_offset INT NULL COMMENT '来源正文起始偏移',
    source_end_offset INT NULL COMMENT '来源正文结束偏移',
    status VARCHAR(32) NOT NULL DEFAULT 'planted' COMMENT '伏笔状态：planted已埋下，pending_payoff待回收，paid_off已回收，abandoned已放弃',
    expected_payoff_chapter_id BIGINT NULL COMMENT '预计回收章节ID',
    actual_payoff_chapter_id BIGINT NULL COMMENT '实际回收章节ID',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除：0否，1是',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_foreshadowing_items_work_status (work_id, status),
    KEY idx_foreshadowing_items_source_chapter_id (source_chapter_id),
    KEY idx_foreshadowing_items_expected_payoff (expected_payoff_chapter_id),
    KEY idx_foreshadowing_items_actual_payoff (actual_payoff_chapter_id),
    CONSTRAINT fk_foreshadowing_items_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_foreshadowing_items_source_chapter_id FOREIGN KEY (source_chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_foreshadowing_items_expected_payoff FOREIGN KEY (expected_payoff_chapter_id) REFERENCES chapters (id),
    CONSTRAINT fk_foreshadowing_items_actual_payoff FOREIGN KEY (actual_payoff_chapter_id) REFERENCES chapters (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='伏笔与线索表';

CREATE TABLE IF NOT EXISTS chapter_summaries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    work_id BIGINT NOT NULL COMMENT '所属作品ID',
    chapter_id BIGINT NOT NULL COMMENT '所属章节ID',
    summary LONGTEXT NOT NULL COMMENT '章节摘要',
    character_changes_json JSON NULL COMMENT '人物变化JSON',
    new_settings_json JSON NULL COMMENT '本章新增设定JSON',
    new_foreshadowing_json JSON NULL COMMENT '本章新增伏笔JSON',
    open_questions_json JSON NULL COMMENT '未决问题JSON',
    summary_status VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '摘要状态：draft草稿，confirmed已确认，outdated已过期',
    content_revision INT NOT NULL DEFAULT 0 COMMENT '摘要对应的正文修订版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除：0否，1是',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_chapter_summaries_chapter_id (chapter_id),
    KEY idx_chapter_summaries_work_gmt_modified (work_id, gmt_modified),
    KEY idx_chapter_summaries_status (summary_status),
    CONSTRAINT fk_chapter_summaries_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_chapter_summaries_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='章节摘要表';

CREATE TABLE IF NOT EXISTS chapter_key_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    work_id BIGINT NOT NULL COMMENT '所属作品ID',
    chapter_id BIGINT NOT NULL COMMENT '所属章节ID',
    event_title VARCHAR(255) NOT NULL COMMENT '事件标题',
    event_content LONGTEXT NOT NULL COMMENT '事件内容',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：plot剧情，character人物，world_rule世界规则，relationship关系，foreshadowing伏笔',
    occurred_order INT NOT NULL DEFAULT 0 COMMENT '本章内事件顺序',
    related_setting_ids_json JSON NULL COMMENT '相关设定ID JSON数组',
    related_foreshadowing_ids_json JSON NULL COMMENT '相关伏笔ID JSON数组',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除：0否，1是',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_chapter_key_events_work_chapter (work_id, chapter_id, occurred_order),
    KEY idx_chapter_key_events_type (event_type),
    CONSTRAINT fk_chapter_key_events_work_id FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_chapter_key_events_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='章节关键事件表';
