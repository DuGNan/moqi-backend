package com.dugnan.moqi.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 验证 AES-GCM 随机 nonce、AAD 绑定和主密钥错误分类。
 */
class CredentialCipherTest {

    private static final byte[] TEST_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void createsCredentialCipherThroughSpringContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(CredentialKeyRing.class, () -> keyRing(TEST_KEY));
            context.register(CredentialCipher.class);
            context.refresh();

            assertThat(context.getBean(CredentialCipher.class)).isNotNull();
        }
    }

    @Test
    void encryptsWithRandomNonceAndDecryptsOriginalValue() {
        CredentialCipher cipher = new CredentialCipher(keyRing(TEST_KEY));
        CredentialIdentity identity = new CredentialIdentity("user-1", "deepseek", "api_key");

        EncryptedCredential first = cipher.encrypt(identity, "test-only-secret");
        EncryptedCredential second = cipher.encrypt(identity, "test-only-secret");

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(cipher.decrypt(identity, first.ciphertext(), first.nonce(), first.keyId()))
                .isEqualTo("test-only-secret");
        assertThat(first.toString()).doesNotContain(first.ciphertext(), first.nonce());
    }

    @Test
    void rejectsCiphertextMovedToAnotherIdentity() {
        CredentialCipher cipher = new CredentialCipher(keyRing(TEST_KEY));
        CredentialIdentity firstIdentity = new CredentialIdentity("user-1", "deepseek", "api_key");
        EncryptedCredential encrypted = cipher.encrypt(firstIdentity, "test-only-secret");

        assertThatThrownBy(() -> cipher.decrypt(
                new CredentialIdentity("user-2", "deepseek", "api_key"),
                encrypted.ciphertext(),
                encrypted.nonce(),
                encrypted.keyId()))
                .isInstanceOf(CredentialSecurityException.class)
                .extracting(exception -> ((CredentialSecurityException) exception).getError())
                .isEqualTo(CredentialSecurityError.AUTHENTICATION_FAILED);
    }

    @Test
    void distinguishesUnknownKeyIdAndWrongKeyAuthentication() {
        CredentialIdentity identity = new CredentialIdentity("user-1", "deepseek", "api_key");
        CredentialCipher cipher = new CredentialCipher(keyRing(TEST_KEY));
        EncryptedCredential encrypted = cipher.encrypt(identity, "test-only-secret");

        assertThatThrownBy(() -> cipher.decrypt(
                identity,
                encrypted.ciphertext(),
                encrypted.nonce(),
                "missing"))
                .isInstanceOf(CredentialSecurityException.class)
                .extracting(exception -> ((CredentialSecurityException) exception).getError())
                .isEqualTo(CredentialSecurityError.KEY_ID_NOT_FOUND);

        byte[] wrongKey =
                "abcdef0123456789abcdef0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CredentialCipher wrongCipher = new CredentialCipher(keyRing(wrongKey));
        assertThatThrownBy(() -> wrongCipher.decrypt(
                identity,
                encrypted.ciphertext(),
                encrypted.nonce(),
                encrypted.keyId()))
                .isInstanceOf(CredentialSecurityException.class)
                .extracting(exception -> ((CredentialSecurityException) exception).getError())
                .isEqualTo(CredentialSecurityError.AUTHENTICATION_FAILED);
    }

    private CredentialKeyRing keyRing(byte[] keyBytes) {
        CredentialKey key = new CredentialKey("key-v1", new SecretKeySpec(keyBytes, "AES"));
        return new CredentialKeyRing() {
            private final Map<String, CredentialKey> keys = Map.of(key.keyId(), key);

            @Override
            public CredentialKey activeKey() {
                return key;
            }

            @Override
            public CredentialKey key(String keyId) {
                CredentialKey selected = keys.get(keyId);
                if (selected == null) {
                    throw new CredentialSecurityException(CredentialSecurityError.KEY_ID_NOT_FOUND);
                }
                return selected;
            }
        };
    }
}
