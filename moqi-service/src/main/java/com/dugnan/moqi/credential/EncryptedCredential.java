package com.dugnan.moqi.credential;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 承载不会通过字符串表示暴露内容的 AES-GCM 加密结果。
 */
public final class EncryptedCredential {

    private final String ciphertext;
    private final String nonce;
    private final String keyId;

    public EncryptedCredential(String ciphertext, String nonce, String keyId) {
        this.ciphertext = ciphertext;
        this.nonce = nonce;
        this.keyId = keyId;
    }

    public String ciphertext() {
        return ciphertext;
    }

    public String nonce() {
        return nonce;
    }

    public String keyId() {
        return keyId;
    }

    @Override
    public String toString() {
        return "EncryptedCredential[ciphertext=****, nonce=****, keyId=" + keyId + "]";
    }
}
