package com.dugnan.moqi.credential;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 提供凭据是否存在及安全掩码的非敏感快照。
 */
public record CredentialSummary(
        boolean configured,
        String maskedValue,
        Integer version) {

    public static CredentialSummary missing() {
        return new CredentialSummary(false, null, 0);
    }
}
