package com.dugnan.moqi.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.credential.entity.LlmCredentialEntity;
import com.dugnan.moqi.credential.mapper.LlmCredentialMapper;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 验证凭据创建、清除、缺失语义和历史主密钥轮换。
 */
@ExtendWith(MockitoExtension.class)
class LlmCredentialServiceTest {

    private static final String PLAINTEXT = "test-only-api-key";

    @Mock
    private LlmCredentialMapper credentialMapper;

    private MutableKeyRing keyRing;
    private CredentialCipher cipher;
    private LlmCredentialService service;

    @BeforeEach
    void setUp() {
        keyRing = new MutableKeyRing();
        cipher = new CredentialCipher(keyRing);
        service = new LlmCredentialService(credentialMapper, cipher, keyRing);
    }

    @Test
    void createsCredentialWithoutExposingPlaintextInEntityString() {
        when(credentialMapper.selectList(any())).thenReturn(List.of());
        when(credentialMapper.insert(any(LlmCredentialEntity.class))).thenReturn(1);

        CredentialSummary result = service.store(identity(), PLAINTEXT);

        ArgumentCaptor<LlmCredentialEntity> captor =
                ArgumentCaptor.forClass(LlmCredentialEntity.class);
        verify(credentialMapper).insert(captor.capture());
        assertThat(result.configured()).isTrue();
        assertThat(result.maskedValue()).isEqualTo("****-key");
        assertThat(captor.getValue().toString())
                .doesNotContain(PLAINTEXT, captor.getValue().getCiphertext(), captor.getValue().getNonce());
    }

    @Test
    void replacesCredentialWithActiveKeyAndNewVersion() {
        keyRing.setActive("old");
        LlmCredentialEntity existing = entity(cipher.encrypt(identity(), PLAINTEXT));
        keyRing.setActive("new");
        when(credentialMapper.selectList(any())).thenReturn(List.of(existing));
        AtomicReference<UpdateWrapper<?>> captured = new AtomicReference<>();
        when(credentialMapper.update(any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return 1;
        });

        CredentialSummary result = service.store(identity(), "test-only-replacement-key");

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.maskedValue()).isEqualTo("****-key");
        assertThat(captured.get().getParamNameValuePairs().values())
                .contains("new")
                .doesNotContain("test-only-replacement-key");
    }

    @Test
    void reusesMatchingExistingCredentialDuringLegacyMigration() {
        LlmCredentialEntity existing = entity(cipher.encrypt(identity(), PLAINTEXT));
        when(credentialMapper.selectList(any())).thenReturn(List.of(existing));

        CredentialSummary result = service.storeLegacyIfAbsent(identity(), PLAINTEXT);

        assertThat(result.configured()).isTrue();
        verify(credentialMapper, never()).insert(any(LlmCredentialEntity.class));
        verify(credentialMapper, never()).update(any(), any());
    }

    @Test
    void rejectsConflictingExistingCredentialDuringLegacyMigration() {
        LlmCredentialEntity existing = entity(cipher.encrypt(identity(), "different-key"));
        when(credentialMapper.selectList(any())).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.storeLegacyIfAbsent(identity(), PLAINTEXT))
                .isInstanceOf(CredentialStateConflictException.class);
        verify(credentialMapper, never()).update(any(), any());
    }

    @Test
    void clearsCredentialByStableIdentity() {
        service.clear(identity());

        verify(credentialMapper).hardDeleteByIdentity("local-user", "deepseek", "api_key");
    }

    @Test
    void distinguishesMissingUserCredential() {
        when(credentialMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.requirePlaintext(identity()))
                .isInstanceOf(CredentialSecurityException.class)
                .extracting(exception -> ((CredentialSecurityException) exception).getError())
                .isEqualTo(CredentialSecurityError.CREDENTIAL_NOT_CONFIGURED);
    }

    @Test
    void rotatesHistoricalKeyToActiveKey() {
        keyRing.setActive("old");
        EncryptedCredential oldEncrypted = cipher.encrypt(identity(), PLAINTEXT);
        LlmCredentialEntity existing = entity(oldEncrypted);
        keyRing.setActive("new");
        when(credentialMapper.selectList(any())).thenReturn(List.of(existing));
        AtomicReference<UpdateWrapper<?>> captured = new AtomicReference<>();
        when(credentialMapper.update(any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return 1;
        });

        int rotated = service.rotateAll();

        assertThat(rotated).isEqualTo(1);
        assertThat(captured.get().getParamNameValuePairs().values())
                .contains("new")
                .doesNotContain(PLAINTEXT);
    }

    private CredentialIdentity identity() {
        return new CredentialIdentity("local-user", "deepseek", "api_key");
    }

    private LlmCredentialEntity entity(EncryptedCredential encrypted) {
        LlmCredentialEntity entity = new LlmCredentialEntity();
        entity.setId(7L);
        entity.setUserId("local-user");
        entity.setProvider("deepseek");
        entity.setCredentialType("api_key");
        entity.setCiphertext(encrypted.ciphertext());
        entity.setNonce(encrypted.nonce());
        entity.setKeyId(encrypted.keyId());
        entity.setMaskedValue("****-key");
        entity.setDeleted(0);
        entity.setVersion(1);
        return entity;
    }

    private static final class MutableKeyRing implements CredentialKeyRing {

        private final Map<String, CredentialKey> keys = Map.of(
                "old",
                key("old", "0123456789abcdef0123456789abcdef"),
                "new",
                key("new", "abcdef0123456789abcdef0123456789"));
        private String active = "new";

        @Override
        public CredentialKey activeKey() {
            return keys.get(active);
        }

        @Override
        public CredentialKey key(String keyId) {
            CredentialKey key = keys.get(keyId);
            if (key == null) {
                throw new CredentialSecurityException(CredentialSecurityError.KEY_ID_NOT_FOUND);
            }
            return key;
        }

        private void setActive(String keyId) {
            active = keyId;
        }

        private static CredentialKey key(String keyId, String value) {
            return new CredentialKey(
                    keyId,
                    new SecretKeySpec(value.getBytes(StandardCharsets.UTF_8), "AES"));
        }
    }
}
