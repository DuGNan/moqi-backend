CREATE TABLE IF NOT EXISTS llm_credentials (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id VARCHAR(64) NOT NULL COMMENT '所属用户ID',
    provider VARCHAR(32) NOT NULL COMMENT '模型供应商标识',
    credential_type VARCHAR(32) NOT NULL COMMENT '凭据类型',
    ciphertext LONGTEXT NOT NULL COMMENT '包含GCM认证标签的Base64密文',
    nonce VARCHAR(64) NOT NULL COMMENT 'Base64随机nonce',
    key_id VARCHAR(128) NOT NULL COMMENT '加密主密钥版本标识',
    masked_value VARCHAR(64) NOT NULL COMMENT '可安全展示的凭据末尾摘要',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除：0否，1是',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_llm_credentials_identity (user_id, provider, credential_type),
    KEY idx_llm_credentials_user_provider (user_id, provider),
    KEY idx_llm_credentials_key_id (key_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='大模型加密凭据表';
