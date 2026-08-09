CREATE TABLE chapter_generation_experiments (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '实验主键',
    work_id BIGINT NOT NULL COMMENT '作品 ID',
    chapter_id BIGINT NOT NULL COMMENT '章节 ID',
    chapter_plan_version_id BIGINT NOT NULL COMMENT '已发布场景规划版本 ID',
    experiment_group_key VARCHAR(64) NOT NULL COMMENT '实验分组键',
    strategy VARCHAR(32) NOT NULL COMMENT '生成策略',
    sample_no INT NOT NULL COMMENT '分组内样本序号',
    experiment_status VARCHAR(32) NOT NULL COMMENT 'running completed failed',
    template_version VARCHAR(128) NOT NULL COMMENT '提示词模板版本',
    input_fingerprint VARCHAR(64) NOT NULL COMMENT '输入 SHA-256',
    provider VARCHAR(64) NOT NULL COMMENT '模型供应商',
    model VARCHAR(128) NOT NULL COMMENT '模型名称',
    config_version INT NULL COMMENT '模型配置版本',
    credential_version INT NULL COMMENT '凭据版本',
    scene_route_json LONGTEXT NOT NULL COMMENT '有序场景规划快照',
    model_call_ids_json TEXT NULL COMMENT '模型调用 ID 列表',
    raw_scene_outputs_json LONGTEXT NULL COMMENT '逐场景原始输出',
    generated_content LONGTEXT NULL COMMENT '最终实验正文',
    word_count INT NULL COMMENT '最终正文字数',
    elapsed_millis BIGINT NULL COMMENT '实验总耗时毫秒',
    error_message VARCHAR(500) NULL COMMENT '安全错误信息',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chapter_generation_experiment_sample (
        chapter_id, experiment_group_key, strategy, sample_no, deleted
    ),
    KEY idx_chapter_generation_experiment_list (
        chapter_id, experiment_group_key, id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='章节正文生成策略隔离实验';
