package com.dugnan.moqi.credential;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 抽象当前写入密钥与历史解密密钥的选择边界。
 */
public interface CredentialKeyRing {

    /**
     * 获取新密文写入使用的活动密钥。
     *
     * @return 活动密钥
     */
    CredentialKey activeKey();

    /**
     * 按版本标识获取历史或当前密钥。
     *
     * @param keyId 密钥版本标识
     * @return 对应密钥
     */
    CredentialKey key(String keyId);
}
