package com.dugnan.moqi.credential;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 表示凭据在并发写入期间发生版本冲突。
 */
public class CredentialStateConflictException extends RuntimeException {

    public CredentialStateConflictException() {
        super("凭据状态已变化");
    }

    public CredentialStateConflictException(Throwable cause) {
        super("凭据状态已变化", cause);
    }
}
