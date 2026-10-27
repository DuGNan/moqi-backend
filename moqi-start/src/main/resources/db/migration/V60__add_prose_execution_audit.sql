ALTER TABLE chapter_conversation_messages
    ADD COLUMN prose_result_json JSON NULL COMMENT '正文会话结果类型及来源，不是修改授权';

ALTER TABLE chapter_selection_assistance
    ADD COLUMN execution_plan_json JSON NULL COMMENT '本轮任务判断及作者授权依据',
    ADD COLUMN execution_stage VARCHAR(32) NULL COMMENT '执行阶段',
    ADD COLUMN result_kind VARCHAR(32) NULL COMMENT '讨论、试写、修改、澄清或无需修改',
    ADD COLUMN clarification_status VARCHAR(32) NULL COMMENT '澄清处置状态',
    ADD COLUMN application_status VARCHAR(32) NULL COMMENT '编辑器应用状态，不代表正文已保存';

CREATE TABLE prose_execution_calls (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    assistance_id BIGINT NOT NULL COMMENT '正文协助请求',
    stage VARCHAR(32) NOT NULL COMMENT '判断、执行或修复',
    messages_json JSON NOT NULL COMMENT '实际发送消息，禁止含凭据',
    options_json JSON NOT NULL COMMENT '实际调用选项',
    raw_output LONGTEXT NULL COMMENT '模型原始输出，含失败输出',
    metadata_json JSON NULL COMMENT '模型及结束原因等调用元数据',
    validation_status VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '待校验、通过或失败',
    validation_error TEXT NULL COMMENT '可定位的校验失败详情',
    repair_of_id BIGINT NULL COMMENT '被修复的调用',
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_prose_calls_request (assistance_id, id),
    CONSTRAINT fk_prose_call_assistance FOREIGN KEY (assistance_id) REFERENCES chapter_selection_assistance(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正文会话分阶段调用审计';
