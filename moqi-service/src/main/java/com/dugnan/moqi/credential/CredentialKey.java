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

    public CredentialKey(String keyId, SecretKey secretKey) {
        this.keyId = keyId;
        this.secretKey = secretKey;
    }

    public String keyId() {
        return keyId;
    }

    public SecretKey secretKey() {
        return secretKey;
    }

    @Override
    public String toString() {
        return "CredentialKey[keyId=" + keyId + ", secretKey=****]";
    }
}
