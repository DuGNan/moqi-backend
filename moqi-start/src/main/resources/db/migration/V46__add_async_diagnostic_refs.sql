ALTER TABLE ai_tasks
    ADD COLUMN diagnostic_ref VARCHAR(64) NULL COMMENT '作者可见的安全诊断引用' AFTER error_message,
    ADD UNIQUE KEY uk_ai_tasks_diagnostic_ref (diagnostic_ref);

ALTER TABLE agent_runs
    ADD COLUMN diagnostic_ref VARCHAR(64) NULL COMMENT '作者可见的安全诊断引用' AFTER error_message,
    ADD UNIQUE KEY uk_agent_runs_diagnostic_ref (diagnostic_ref);
