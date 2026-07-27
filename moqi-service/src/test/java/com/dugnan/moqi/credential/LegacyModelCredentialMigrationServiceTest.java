package com.dugnan.moqi.credential;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.config.entity.UserConfigEntity;
import com.dugnan.moqi.config.mapper.UserConfigMapper;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 验证旧明文凭据迁移的幂等、失败关闭和 CAS 语义。
 */
@ExtendWith(MockitoExtension.class)
class LegacyModelCredentialMigrationServiceTest {

    private static final String LEGACY_KEY = "test-only-legacy-key";

    @Mock
    private UserConfigMapper configMapper;
    @Mock
    private LlmCredentialService credentialService;

    private LegacyModelCredentialMigrationService service;

    @BeforeEach
    void setUp() {
        service = new LegacyModelCredentialMigrationService(
                configMapper,
                credentialService,
                new ObjectMapper());
    }

    @Test
    void storesCredentialAndRemovesPlaintextWithCas() {
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                "{\"provider\":\"deepseek\",\"apiKey\":\"" + LEGACY_KEY + "\",\"defaultModel\":\"m\"}")));
        when(configMapper.update(any(), any())).thenAnswer(invocation -> {
            UpdateWrapper<?> update = invocation.getArgument(1);
            assertThatNoSecret(update);
            return 1;
        });

        service.migrate();

        verify(credentialService).storeLegacyIfAbsent(any(), org.mockito.ArgumentMatchers.eq(LEGACY_KEY));
        verify(configMapper).update(any(), any());
    }

    @Test
    void repeatedMigrationWithoutPlaintextDoesNothing() {
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                "{\"provider\":\"deepseek\",\"defaultModel\":\"m\"}")));

        service.migrate();

        verify(credentialService, never()).storeLegacyIfAbsent(any(), any());
        verify(configMapper, never()).update(any(), any());
    }

    @Test
    void missingMasterKeyFailsClosedBeforeConfigCleanup() {
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                "{\"provider\":\"deepseek\",\"apiKey\":\"" + LEGACY_KEY + "\"}")));
        org.mockito.Mockito.doThrow(new CredentialSecurityException(
                        CredentialSecurityError.MASTER_KEY_NOT_CONFIGURED))
                .when(credentialService)
                .storeLegacyIfAbsent(any(), any());

        assertThatThrownBy(service::migrate)
                .isInstanceOf(CredentialSecurityException.class);
        verify(configMapper, never()).update(any(), any());
    }

    @Test
    void credentialWriteFailureLeavesPlaintextConfigUntouched() {
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                "{\"provider\":\"deepseek\",\"apiKey\":\"" + LEGACY_KEY + "\"}")));
        org.mockito.Mockito.doThrow(new CredentialStateConflictException())
                .when(credentialService)
                .storeLegacyIfAbsent(any(), any());

        assertThatThrownBy(service::migrate)
                .isInstanceOf(CredentialStateConflictException.class);
        verify(configMapper, never()).update(any(), any());
    }

    @Test
    void existingMatchingCredentialStillRemovesLegacyPlaintext() {
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                "{\"provider\":\"deepseek\",\"apiKey\":\"" + LEGACY_KEY + "\"}")));
        when(configMapper.update(any(), any())).thenReturn(1);

        service.migrate();

        verify(credentialService).storeLegacyIfAbsent(any(), org.mockito.ArgumentMatchers.eq(LEGACY_KEY));
        verify(configMapper).update(any(), any());
    }

    @Test
    void casConflictFailsWholeMigration() {
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                "{\"provider\":\"deepseek\",\"apiKey\":\"" + LEGACY_KEY + "\"}")));
        when(configMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(service::migrate)
                .isInstanceOf(CredentialStateConflictException.class);
    }

    @Test
    void malformedLegacyConfigFailsClosed() {
        when(configMapper.selectList(any())).thenReturn(List.of(config("not-json")));

        assertThatThrownBy(service::migrate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configId=602");
        verify(credentialService, never()).storeLegacyIfAbsent(any(), any());
    }

    private void assertThatNoSecret(UpdateWrapper<?> update) {
        String values = update.getParamNameValuePairs().values().toString();
        org.assertj.core.api.Assertions.assertThat(values)
                .doesNotContain(LEGACY_KEY)
                .doesNotContain("\"apiKey\"");
    }

    private UserConfigEntity config(String value) {
        UserConfigEntity config = new UserConfigEntity();
        config.setId(602L);
        config.setUserId("local-user");
        config.setConfigKey("model.active");
        config.setConfigValue(value);
        config.setDeleted(0);
        config.setVersion(2);
        return config;
    }
}
