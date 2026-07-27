package com.dugnan.moqi.credential;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 仅携带安全分类和固定消息的凭据安全异常。
 */
public class CredentialSecurityException extends RuntimeException {

    private final CredentialSecurityError error;

    public CredentialSecurityException(CredentialSecurityError error) {
        super(error.safeMessage());
        this.error = error;
    }

    public CredentialSecurityException(CredentialSecurityError error, Throwable cause) {
        super(error.safeMessage(), cause);
        this.error = error;
    }

    public CredentialSecurityError getError() {
        return error;
    }
}
