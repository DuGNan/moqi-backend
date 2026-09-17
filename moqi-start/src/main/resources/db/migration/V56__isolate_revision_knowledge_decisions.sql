ALTER TABLE story_knowledge_candidates
    ADD COLUMN decision_resolution VARCHAR(16) NULL COMMENT '待发布知识决策方式',
    ADD COLUMN decision_target_id BIGINT NULL COMMENT '决策冻结的目标知识 ID',
    ADD COLUMN decision_target_version INT NULL COMMENT '决策冻结的目标版本';

CREATE TABLE story_release_knowledge_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    snapshot_json JSON NOT NULL COMMENT '四类知识的完整内容快照，含空集合',
    sealed TINYINT NOT NULL DEFAULT 0 COMMENT '历史版本封存后禁止覆盖',
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_release_knowledge_snapshot (release_id),
    KEY idx_release_knowledge_snapshot_work (work_id, release_id),
    CONSTRAINT fk_release_knowledge_snapshot_work FOREIGN KEY (work_id) REFERENCES works(id),
    CONSTRAINT fk_release_knowledge_snapshot_release FOREIGN KEY (release_id) REFERENCES story_releases(id)
) COMMENT='Story Release 知识内容与回退快照';
