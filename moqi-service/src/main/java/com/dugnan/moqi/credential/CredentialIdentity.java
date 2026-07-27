package com.dugnan.moqi.credential;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 定义参与凭据查询和 AES-GCM AAD 的稳定身份。
 */
public record CredentialIdentity(
        String userId,
        String provider,
        String credentialType) {

    public String aad() {
        return userId + "\n" + provider + "\n" + credentialType;
    }
}
