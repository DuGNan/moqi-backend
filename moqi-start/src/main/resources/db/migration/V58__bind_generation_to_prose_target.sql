-- V57 属于独立提示词编译改造，不修改其校验值或结构。
-- 可空字段兼容旧生成链路；回退时须先保留生成目标和原文快照。
ALTER TABLE chapter_generations
    ADD COLUMN result_mode VARCHAR(32) NULL COMMENT 'replace_current 原位更新或 new_candidate 新候选',
    ADD COLUMN target_object_id VARCHAR(64) NULL COMMENT '稳定正式正文或候选对象',
    ADD COLUMN target_version INT NULL,
    ADD COLUMN target_content_hash CHAR(64) NULL,
    ADD COLUMN target_content_snapshot LONGTEXT NULL COMMENT '生成前已保存原文快照',
    ADD COLUMN writeback_status VARCHAR(32) NULL COMMENT '写回状态 pending/succeeded/conflict/failed',
    ADD COLUMN writeback_message VARCHAR(512) NULL,
    ADD KEY idx_generation_target (chapter_id, target_object_id, id),
    ADD KEY idx_generation_writeback (writeback_status, generation_status, id);
