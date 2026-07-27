package com.dugnan.moqi.credential.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 映射不允许自动输出密文与 nonce 的大模型凭据记录。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("llm_credentials")
public class LlmCredentialEntity extends BaseEntity {

    private String userId;

    private String provider;

    private String credentialType;

    private String ciphertext;

    private String nonce;

    private String keyId;

    private String maskedValue;

    @Override
    public String toString() {
        return "LlmCredentialEntity[id=" + getId()
                + ", userId=" + userId
                + ", provider=" + provider
                + ", credentialType=" + credentialType
                + ", ciphertext=****, nonce=****, keyId=" + keyId
                + ", maskedValue=" + maskedValue
                + ", deleted=" + getDeleted()
                + ", version=" + getVersion() + "]";
    }
}
