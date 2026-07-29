package com.dugnan.moqi.credential;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.credential.entity.LlmCredentialEntity;
import com.dugnan.moqi.credential.mapper.LlmCredentialMapper;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 编排大模型凭据的创建、替换、读取、清除与主密钥轮换。
 */
@Service
public class LlmCredentialService {

    private static final int VISIBLE_SUFFIX_LENGTH = 4;

    private final LlmCredentialMapper credentialMapper;
    private final CredentialCipher credentialCipher;
    private final CredentialKeyRing keyRing;

    /**
     * 创建 LLM 凭据服务。
     *
     * @param credentialMapper 凭据数据访问对象
     * @param credentialCipher 凭据加密器
     * @param keyRing 凭据主密钥环
     */
    public LlmCredentialService(
            LlmCredentialMapper credentialMapper,
            CredentialCipher credentialCipher,
            CredentialKeyRing keyRing) {
        this.credentialMapper = credentialMapper;
        this.credentialCipher = credentialCipher;
        this.keyRing = keyRing;
    }

    /**
     * 查询不含敏感值的凭据摘要。
     *
     * @param identity 凭据稳定身份
     * @return 凭据摘要
     */
    public CredentialSummary summary(CredentialIdentity identity) {
        LlmCredentialEntity entity = find(identity);
        if (entity == null) {
            return CredentialSummary.missing();
        }
        return new CredentialSummary(true, entity.getMaskedValue(), version(entity));
    }

    /**
     * 读取并解密已配置凭据。
     *
     * @param identity 凭据稳定身份
     * @return 凭据明文
     * @throws CredentialSecurityException 凭据未配置或无法安全解密
     */
    public String requirePlaintext(CredentialIdentity identity) {
        LlmCredentialEntity entity = find(identity);
        if (entity == null) {
            throw new CredentialSecurityException(CredentialSecurityError.CREDENTIAL_NOT_CONFIGURED);
        }
        return decrypt(identity, entity);
    }

    /**
     * 创建或替换指定身份的加密凭据。
     *
     * @param identity 凭据稳定身份
     * @param plaintext 凭据明文
     * @return 更新后的安全摘要
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public CredentialSummary store(CredentialIdentity identity, String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            throw new IllegalArgumentException("凭据不能为空");
        }
        LlmCredentialEntity existing = find(identity);
        EncryptedCredential encrypted = credentialCipher.encrypt(identity, plaintext.trim());
        if (existing == null) {
            return insert(identity, plaintext.trim(), encrypted);
        }
        return update(identity, existing, plaintext.trim(), encrypted);
    }

    /**
     * 仅在凭据不存在时迁移旧明文，存在时要求内容一致。
     *
     * @param identity 凭据稳定身份
     * @param plaintext 旧配置中的凭据明文
     * @return 已存在或新建凭据的安全摘要
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public CredentialSummary storeLegacyIfAbsent(CredentialIdentity identity, String plaintext) {
        keyRing.activeKey();
        LlmCredentialEntity existing = find(identity);
        if (existing != null) {
            String existingPlaintext = decrypt(identity, existing);
            if (!existingPlaintext.equals(plaintext)) {
                throw new CredentialStateConflictException();
            }
            return new CredentialSummary(true, existing.getMaskedValue(), version(existing));
        }
        return store(identity, plaintext);
    }

    /**
     * 按稳定身份物理清除凭据。
     *
     * @param identity 凭据稳定身份
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public void clear(CredentialIdentity identity) {
        credentialMapper.hardDeleteByIdentity(
                identity.userId(),
                identity.provider(),
                identity.credentialType());
    }

    /**
     * 将全部历史密钥版本凭据轮换到活动主密钥。
     *
     * @return 已轮换凭据数量
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public int rotateAll() {
        CredentialKey activeKey = keyRing.activeKey();
        List<LlmCredentialEntity> credentials = credentialMapper.selectList(
                new LambdaQueryWrapper<LlmCredentialEntity>()
                        .eq(LlmCredentialEntity::getDeleted, 0));
        int rotated = 0;
        for (LlmCredentialEntity credential : credentials) {
            if (activeKey.keyId().equals(credential.getKeyId())) {
                continue;
            }
            CredentialIdentity identity = identity(credential);
            String plaintext = decrypt(identity, credential);
            EncryptedCredential encrypted = credentialCipher.encrypt(identity, plaintext);
            update(identity, credential, plaintext, encrypted);
            rotated++;
        }
        return rotated;
    }

    private CredentialSummary insert(
            CredentialIdentity identity,
            String plaintext,
            EncryptedCredential encrypted) {
        LlmCredentialEntity entity = new LlmCredentialEntity();
        entity.setUserId(identity.userId());
        entity.setProvider(identity.provider());
        entity.setCredentialType(identity.credentialType());
        applyEncrypted(entity, encrypted, mask(plaintext));
        entity.setDeleted(0);
        entity.setVersion(1);
        LocalDateTime now = LocalDateTime.now();
        entity.setGmtCreate(now);
        entity.setGmtModified(now);
        try {
            if (credentialMapper.insert(entity) != 1) {
                throw new CredentialStateConflictException();
            }
        } catch (DuplicateKeyException exception) {
            throw new CredentialStateConflictException(exception);
        }
        return new CredentialSummary(true, entity.getMaskedValue(), 1);
    }

    private CredentialSummary update(
            CredentialIdentity identity,
            LlmCredentialEntity existing,
            String plaintext,
            EncryptedCredential encrypted) {
        int currentVersion = version(existing);
        int nextVersion = currentVersion + 1;
        String maskedValue = mask(plaintext);
        UpdateWrapper<LlmCredentialEntity> update = new UpdateWrapper<LlmCredentialEntity>()
                .eq("id", existing.getId())
                .eq("user_id", identity.userId())
                .eq("provider", identity.provider())
                .eq("credential_type", identity.credentialType())
                .eq("deleted", 0)
                .eq("version", currentVersion)
                .set("ciphertext", encrypted.ciphertext())
                .set("nonce", encrypted.nonce())
                .set("key_id", encrypted.keyId())
                .set("masked_value", maskedValue)
                .set("version", nextVersion)
                .set("gmt_modified", LocalDateTime.now());
        if (credentialMapper.update(null, update) != 1) {
            throw new CredentialStateConflictException();
        }
        return new CredentialSummary(true, maskedValue, nextVersion);
    }

    private void applyEncrypted(
            LlmCredentialEntity entity,
            EncryptedCredential encrypted,
            String maskedValue) {
        entity.setCiphertext(encrypted.ciphertext());
        entity.setNonce(encrypted.nonce());
        entity.setKeyId(encrypted.keyId());
        entity.setMaskedValue(maskedValue);
    }

    private String decrypt(CredentialIdentity identity, LlmCredentialEntity entity) {
        return credentialCipher.decrypt(
                identity,
                entity.getCiphertext(),
                entity.getNonce(),
                entity.getKeyId());
    }

    private LlmCredentialEntity find(CredentialIdentity identity) {
        return credentialMapper.selectList(
                        new LambdaQueryWrapper<LlmCredentialEntity>()
                                .eq(LlmCredentialEntity::getUserId, identity.userId())
                                .eq(LlmCredentialEntity::getProvider, identity.provider())
                                .eq(LlmCredentialEntity::getCredentialType, identity.credentialType())
                                .eq(LlmCredentialEntity::getDeleted, 0)
                                .orderByDesc(LlmCredentialEntity::getId))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private CredentialIdentity identity(LlmCredentialEntity entity) {
        return new CredentialIdentity(
                entity.getUserId(),
                entity.getProvider(),
                entity.getCredentialType());
    }

    private int version(LlmCredentialEntity entity) {
        return entity.getVersion() == null ? 0 : entity.getVersion();
    }

    private String mask(String plaintext) {
        if (plaintext.length() <= VISIBLE_SUFFIX_LENGTH) {
            return "****";
        }
        return "****" + plaintext.substring(plaintext.length() - VISIBLE_SUFFIX_LENGTH);
    }
}
