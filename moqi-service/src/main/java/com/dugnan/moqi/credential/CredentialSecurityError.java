package com.dugnan.moqi.credential;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 区分凭据加密边界内部的安全失败类别。
 */
public enum CredentialSecurityError {
    /** 未配置活动主密钥。 */
    MASTER_KEY_NOT_CONFIGURED("凭据主密钥未配置"),
    /** 密文引用的密钥版本不存在。 */
    KEY_ID_NOT_FOUND("凭据主密钥版本不可用"),
    /** GCM 身份或认证标签校验失败。 */
    AUTHENTICATION_FAILED("凭据解密认证失败"),
    /** 用户模型凭据尚未配置。 */
    CREDENTIAL_NOT_CONFIGURED("用户模型凭据未配置"),
    /** 运行时密钥配置格式或长度非法。 */
    INVALID_KEY_CONFIGURATION("凭据主密钥配置无效");

    private final String safeMessage;

    CredentialSecurityError(String safeMessage) {
        this.safeMessage = safeMessage;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
