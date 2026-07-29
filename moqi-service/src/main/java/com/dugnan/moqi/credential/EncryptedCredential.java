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

    /**
     * 创建 AES-GCM 加密结果。
     *
     * @param ciphertext Base64 密文
     * @param nonce Base64 nonce
     * @param keyId 密钥版本标识
     */
    public EncryptedCredential(String ciphertext, String nonce, String keyId) {
        this.ciphertext = ciphertext;
        this.nonce = nonce;
        this.keyId = keyId;
    }

    /**
     * 获取 Base64 密文。
     *
     * @return Base64 密文
     */
    public String ciphertext() {
        return ciphertext;
    }

    /**
     * 获取 Base64 nonce。
     *
     * @return Base64 nonce
     */
    public String nonce() {
        return nonce;
    }

    /**
     * 获取密钥版本标识。
     *
     * @return 密钥版本标识
     */
    public String keyId() {
        return keyId;
    }

    @Override
    public String toString() {
        return "EncryptedCredential[ciphertext=****, nonce=****, keyId=" + keyId + "]";
    }
}
