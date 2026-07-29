package com.dugnan.moqi.credential;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 表示凭据在并发写入期间发生版本冲突。
 */
public class CredentialStateConflictException extends RuntimeException {

    /**
     * 创建凭据状态冲突异常。
     */
    public CredentialStateConflictException() {
        super("凭据状态已变化");
    }

    /**
     * 创建保留原始原因的凭据状态冲突异常。
     *
     * @param cause 原始异常
     */
    public CredentialStateConflictException(Throwable cause) {
        super("凭据状态已变化", cause);
    }
}
