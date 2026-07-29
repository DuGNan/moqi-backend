package com.dugnan.moqi.credential;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 仅携带安全分类和固定消息的凭据安全异常。
 */
public class CredentialSecurityException extends RuntimeException {

    private final CredentialSecurityError error;

    /**
     * 使用安全错误分类创建异常。
     *
     * @param error 安全错误分类
     */
    public CredentialSecurityException(CredentialSecurityError error) {
        super(error.safeMessage());
        this.error = error;
    }

    /**
     * 使用安全错误分类和原始原因创建异常。
     *
     * @param error 安全错误分类
     * @param cause 原始异常
     */
    public CredentialSecurityException(CredentialSecurityError error, Throwable cause) {
        super(error.safeMessage(), cause);
        this.error = error;
    }

    /**
     * 获取安全错误分类。
     *
     * @return 安全错误分类
     */
    public CredentialSecurityError getError() {
        return error;
    }
}
