package com.dugnan.moqi.credential;

import javax.crypto.SecretKey;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 保存不可通过字符串表示泄露的主密钥版本。
 */
public final class CredentialKey {

    private final String keyId;
    private final SecretKey secretKey;

    /**
     * 创建指定版本的凭据主密钥。
     *
     * @param keyId 密钥版本标识
     * @param secretKey AES 主密钥
     */
    public CredentialKey(String keyId, SecretKey secretKey) {
        this.keyId = keyId;
        this.secretKey = secretKey;
    }

    /**
     * 获取密钥版本标识。
     *
     * @return 密钥版本标识
     */
    public String keyId() {
        return keyId;
    }

    /**
     * 获取 AES 主密钥。
     *
     * @return AES 主密钥
     */
    public SecretKey secretKey() {
        return secretKey;
    }

    @Override
    public String toString() {
        return "CredentialKey[keyId=" + keyId + ", secretKey=****]";
    }
}
