package com.dugnan.moqi.credential;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 从受保护运行时配置解析当前与历史 AES 主密钥。
 */
@Component
public class EnvironmentCredentialKeyRing implements CredentialKeyRing {

    private static final int AES_256_KEY_BYTES = 32;
    private static final String KEY_ENTRY_DELIMITER = ",";

    private final String activeKeyId;
    private final Map<String, CredentialKey> keys;

    public EnvironmentCredentialKeyRing(
            @Value("${moqi.security.credentials.active-key-id:}") String activeKeyId,
            @Value("${moqi.security.credentials.keys:}") String configuredKeys) {
        this.activeKeyId = activeKeyId == null ? "" : activeKeyId.trim();
        this.keys = parseKeys(configuredKeys);
    }

    @Override
    public CredentialKey activeKey() {
        if (!StringUtils.hasText(activeKeyId)) {
            throw new CredentialSecurityException(CredentialSecurityError.MASTER_KEY_NOT_CONFIGURED);
        }
        return key(activeKeyId);
    }

    @Override
    public CredentialKey key(String keyId) {
        CredentialKey key = keys.get(keyId);
        if (key == null) {
            throw new CredentialSecurityException(CredentialSecurityError.KEY_ID_NOT_FOUND);
        }
        return key;
    }

    private Map<String, CredentialKey> parseKeys(String configuredKeys) {
        Map<String, CredentialKey> parsed = new LinkedHashMap<>();
        if (!StringUtils.hasText(configuredKeys)) {
            return Map.of();
        }
        for (String entry : configuredKeys.split(KEY_ENTRY_DELIMITER)) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) {
                throw invalidConfiguration();
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(parts[1].trim());
            } catch (IllegalArgumentException exception) {
                throw new CredentialSecurityException(
                        CredentialSecurityError.INVALID_KEY_CONFIGURATION,
                        exception);
            }
            if (decoded.length != AES_256_KEY_BYTES || parsed.containsKey(parts[0].trim())) {
                throw invalidConfiguration();
            }
            parsed.put(
                    parts[0].trim(),
                    new CredentialKey(parts[0].trim(), new SecretKeySpec(decoded, "AES")));
        }
        return Map.copyOf(parsed);
    }

    private CredentialSecurityException invalidConfiguration() {
        return new CredentialSecurityException(CredentialSecurityError.INVALID_KEY_CONFIGURATION);
    }
}
