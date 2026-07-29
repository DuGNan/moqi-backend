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

    /**
     * 创建未配置凭据的安全摘要。
     *
     * @return 未配置凭据摘要
     */
    public static CredentialSummary missing() {
        return new CredentialSummary(false, null, 0);
    }
}
