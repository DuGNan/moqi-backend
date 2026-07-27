package com.dugnan.moqi.config.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.dto.UserConfigModels.UpdateUserConfigRequest;
import com.dugnan.moqi.config.dto.UserConfigModels.UserConfigSaved;
import com.dugnan.moqi.config.entity.UserConfigEntity;
import com.dugnan.moqi.config.mapper.UserConfigMapper;
import com.dugnan.moqi.credential.CredentialSummary;
import com.dugnan.moqi.credential.LlmCredentialService;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderError;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 验证用户配置白名单、乐观锁、敏感键保护和模型状态规则。
 */
@ExtendWith(MockitoExtension.class)
class UserConfigServiceImplTest {

    private static final String TEST_API_KEY = "test-only-deepseek-key";

    @Mock
    private UserConfigMapper configMapper;

    @Mock
    private LlmProviderFactory providerFactory;

    @Mock
    private LlmProvider provider;

    @Mock
    private LlmCredentialService credentialService;

    private ObjectMapper objectMapper;
    private UserConfigServiceImpl service;

    /**
     * 初始化用户配置服务。
     */
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        org.mockito.Mockito.lenient()
                .when(credentialService.summary(any()))
                .thenReturn(CredentialSummary.missing());
        service = new UserConfigServiceImpl(
                configMapper,
                objectMapper,
                providerFactory,
                credentialService);
    }

    /**
     * 验证缺失配置返回固定本地用户、版本零和空对象。
     */
    @Test
    void returnsVirtualEmptyConfigWhenMissing() {
        when(configMapper.selectList(any())).thenReturn(List.of());

        var result = service.getConfig("writing.preferences");

        assertThat(result.id()).isNull();
        assertThat(result.userId()).isEqualTo("local-user");
        assertThat(result.version()).isZero();
        assertThat(result.configValue().isObject()).isTrue();
        assertThat(result.configValue().isEmpty()).isTrue();
    }

    /**
     * 验证模型配置缺失时也明确返回未配置 Key，且不伪造掩码。
     */
    @Test
    void reportsApiKeyNotConfiguredWhenModelConfigIsMissing() {
        when(configMapper.selectList(any())).thenReturn(List.of());

        var result = service.getConfig("model.active");

        assertThat(result.configValue().get("apiKeyConfigured").asBoolean()).isFalse();
        assertThat(result.configValue().has("apiKeyMasked")).isFalse();
    }

    /**
     * 验证只允许四个公开配置键。
     */
    @Test
    void rejectsConfigKeyOutsideWhitelist() {
        assertThatThrownBy(() -> service.getConfig("model.private"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    /**
     * 验证 baseVersion 为零时创建缺失配置。
     *
     * @throws Exception 测试 JSON 解析失败
     */
    @Test
    void createsMissingConfigAtVersionOne() throws Exception {
        when(configMapper.selectList(any())).thenReturn(List.of());
        when(configMapper.insert(any(UserConfigEntity.class))).thenAnswer(invocation -> {
            UserConfigEntity entity = invocation.getArgument(0);
            entity.setId(601L);
            return 1;
        });

        var result = service.updateConfig(
                "appearance.preferences",
                new UpdateUserConfigRequest(0, objectMapper.readTree("{\"theme\":\"light\"}")));

        assertThat(result.id()).isEqualTo(601L);
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.gmtModified()).isNotNull();
        verify(configMapper).insert(org.mockito.ArgumentMatchers.<UserConfigEntity>argThat(
                entity -> entity.getVersion() == 1));
    }

    /**
     * 验证模型配置创建时只写非敏感元数据，并把 Key 交给独立凭据服务。
     */
    @Test
    void createsNormalizedDeepSeekModelConfigWithApiKey() {
        when(configMapper.selectList(any())).thenReturn(List.of());
        when(configMapper.insert(any(UserConfigEntity.class))).thenAnswer(invocation -> {
            UserConfigEntity entity = invocation.getArgument(0);
            entity.setId(602L);
            return 1;
        });

        service.updateConfig(
                "model.active",
                new UpdateUserConfigRequest(
                        0,
                        objectMapper.createObjectNode().put("defaultModel", "deepseek-v4-pro"),
                        TEST_API_KEY,
                        false));

        verify(configMapper).insert(org.mockito.ArgumentMatchers.<UserConfigEntity>argThat(entity -> {
            String value = entity.getConfigValue();
            return value.contains("\"provider\":\"deepseek\"")
                    && value.contains("\"baseUrl\":\"https://api.deepseek.com\"")
                    && value.contains("\"defaultModel\":\"deepseek-v4-pro\"")
                    && !value.contains("apiKey")
                    && !value.contains(TEST_API_KEY)
                    && value.contains("\"lastTestStatus\":\"not_tested\"");
        }));
        verify(credentialService).store(any(), org.mockito.ArgumentMatchers.eq(TEST_API_KEY));
    }

    /**
     * 验证读取模型配置时只返回 Key 配置标记与末四位摘要。
     */
    @Test
    void masksStoredModelApiKeyOnRead() {
        when(credentialService.summary(any()))
                .thenReturn(new CredentialSummary(true, "****-key", 1));
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                602L,
                "model.active",
                "{\"provider\":\"deepseek\",\"apiKey\":\"" + TEST_API_KEY + "\"}",
                3)));

        var result = service.getConfig("model.active");

        assertThat(result.configValue().has("apiKey")).isFalse();
        assertThat(result.configValue().get("apiKeyConfigured").asBoolean()).isTrue();
        assertThat(result.configValue().get("apiKeyMasked").asText()).isEqualTo("****-key");
        assertThat(result.configValue().toString()).doesNotContain(TEST_API_KEY);
    }

    /**
     * 验证长度不超过四位的 Key 不会通过摘要被完整回显。
     */
    @Test
    void fullyMasksShortStoredModelApiKey() {
        when(credentialService.summary(any()))
                .thenReturn(new CredentialSummary(true, "****", 1));
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                602L,
                "model.active",
                "{\"provider\":\"deepseek\",\"apiKey\":\"tiny\"}",
                3)));

        var result = service.getConfig("model.active");

        assertThat(result.configValue().get("apiKeyMasked").asText()).isEqualTo("****");
        assertThat(result.configValue().toString()).doesNotContain("tiny");
    }

    /**
     * 验证同时提交 Key 和删除标记会被拒绝。
     */
    @Test
    void rejectsApiKeyAndClearFlagTogether() {
        assertThatThrownBy(() -> service.updateConfig(
                        "model.active",
                        new UpdateUserConfigRequest(0, objectMapper.createObjectNode(), TEST_API_KEY, true)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(configMapper, never()).insert(any(UserConfigEntity.class));
    }

    /**
     * 验证空白 Key 与删除标记同时出现也按字段冲突处理。
     */
    @Test
    void rejectsBlankApiKeyAndClearFlagTogether() {
        assertThatThrownBy(() -> service.updateConfig(
                        "model.active",
                        new UpdateUserConfigRequest(0, objectMapper.createObjectNode(), " ", true)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    /**
     * 验证非模型配置不能使用模型密钥字段。
     */
    @Test
    void rejectsApiKeyFieldsForNonModelConfig() {
        assertThatThrownBy(() -> service.updateConfig(
                        "writing.preferences",
                        new UpdateUserConfigRequest(0, objectMapper.createObjectNode(), TEST_API_KEY, false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(configMapper, never()).insert(any(UserConfigEntity.class));
    }

    /**
     * 验证空白 Key 不触碰凭据，显式删除由独立凭据服务执行。
     */
    @Test
    void preservesOrClearsStoredApiKeyExplicitly() {
        UserConfigEntity existing = config(
                602L,
                "model.active",
                "{\"provider\":\"deepseek\",\"apiKey\":\"" + TEST_API_KEY + "\"}",
                2);
        when(configMapper.selectList(any())).thenReturn(List.of(existing));
        when(configMapper.update(any(), any())).thenReturn(1);

        service.updateConfig(
                "model.active",
                new UpdateUserConfigRequest(2, objectMapper.createObjectNode(), "  ", false));
        service.updateConfig(
                "model.active",
                new UpdateUserConfigRequest(2, objectMapper.createObjectNode(), null, true));

        verify(credentialService, never()).store(any(), any());
        verify(credentialService).clear(any());
    }

    /**
     * 验证非空新 Key 只交给独立凭据服务替换。
     */
    @Test
    void replacesStoredApiKey() {
        String replacementKey = "replacement-test-key";
        when(configMapper.selectList(any())).thenReturn(List.of(deepSeekConfig(2)));
        when(configMapper.update(any(), any())).thenReturn(1);

        service.updateConfig(
                "model.active",
                new UpdateUserConfigRequest(2, objectMapper.createObjectNode(), replacementKey, false));

        verify(credentialService).store(any(), org.mockito.ArgumentMatchers.eq(replacementKey));
    }

    /**
     * 验证模型白名单之外的名称不会落库。
     */
    @Test
    void rejectsUnsupportedDeepSeekModel() {
        when(configMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateConfig(
                        "model.active",
                        new UpdateUserConfigRequest(
                                0,
                                objectMapper.createObjectNode().put("defaultModel", "deepseek-unknown"),
                                TEST_API_KEY,
                                false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(configMapper, never()).insert(any(UserConfigEntity.class));
    }

    /**
     * 验证两个客户端都基于缺失态版本零创建时，唯一键竞争的失败方返回版本冲突。
     *
     * @throws Exception 测试 JSON 解析失败
     */
    @Test
    void rejectsSecondMissingConfigCreateWhenUniqueKeyRaceIsLost() throws Exception {
        CyclicBarrier insertBarrier = new CyclicBarrier(2);
        AtomicBoolean isCreated = new AtomicBoolean();
        when(configMapper.selectList(any())).thenReturn(List.of());
        when(configMapper.insert(any(UserConfigEntity.class))).thenAnswer(invocation -> {
            UserConfigEntity entity = invocation.getArgument(0);
            insertBarrier.await(5, TimeUnit.SECONDS);
            if (isCreated.compareAndSet(false, true)) {
                entity.setId(601L);
                return 1;
            }
            throw new DuplicateKeyException("duplicate user config");
        });

        List<Object> outcomes = runConcurrently(
                () -> service.updateConfig(
                        "sync.preferences",
                        new UpdateUserConfigRequest(0, objectMapper.createObjectNode().put("enabled", true))),
                () -> service.updateConfig(
                        "sync.preferences",
                        new UpdateUserConfigRequest(0, objectMapper.createObjectNode().put("enabled", false))));

        assertThat(outcomes).filteredOn(UserConfigSaved.class::isInstance)
                .singleElement()
                .extracting(result -> ((UserConfigSaved) result).version())
                .isEqualTo(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance)
                .singleElement()
                .satisfies(result -> assertThat((BusinessException) result)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONFIG_VERSION_CONFLICT)
                        .hasCauseInstanceOf(DuplicateKeyException.class));
        verify(configMapper, times(2)).insert(any(UserConfigEntity.class));
    }

    /**
     * 验证更新配置时按 baseVersion 做乐观锁并递增版本。
     *
     * @throws Exception 测试 JSON 解析失败
     */
    @Test
    void updatesConfigWithOptimisticLock() throws Exception {
        UserConfigEntity existing = config(601L, "writing.preferences", "{\"tone\":\"calm\"}", 2);
        when(configMapper.selectList(any())).thenReturn(List.of(existing));
        when(configMapper.update(any(), any())).thenReturn(1);

        var result = service.updateConfig(
                "writing.preferences",
                new UpdateUserConfigRequest(2, objectMapper.readTree("{\"tone\":\"direct\"}")));

        assertThat(result.version()).isEqualTo(3);
        verify(configMapper).update(any(), any());
    }

    /**
     * 验证过期 baseVersion 返回冲突。
     *
     * @throws Exception 测试 JSON 解析失败
     */
    @Test
    void rejectsStaleConfigVersion() throws Exception {
        when(configMapper.selectList(any()))
                .thenReturn(List.of(config(601L, "writing.preferences", "{}", 2)));

        assertThatThrownBy(() -> service.updateConfig(
                "writing.preferences",
                new UpdateUserConfigRequest(1, objectMapper.readTree("{}"))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CONFIG_VERSION_CONFLICT);
    }

    /**
     * 验证任意层级、任意大小写的敏感键都会被拒绝且不会落库。
     *
     * @throws Exception 测试 JSON 解析失败
     */
    @Test
    void rejectsNestedSensitiveKeys() throws Exception {
        List<String> sensitiveKeys = List.of(
                "apiKey",
                "accessKey",
                "privateKey",
                "apiKeyUnmasked",
                "token",
                "tokens",
                "clientSecret",
                "clientSecrets",
                "password",
                "credential");
        for (String sensitiveKey : sensitiveKeys) {
            UpdateUserConfigRequest request = new UpdateUserConfigRequest(
                    0,
                    objectMapper.readTree(
                            "{\"providers\":[{\"nested\":{\"" + sensitiveKey + "\":\"plain\"}}]}"));

            assertThatThrownBy(() -> service.updateConfig("model.active", request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
        verify(configMapper, never()).insert(any(UserConfigEntity.class));
    }

    /**
     * 验证明文、对象和数组不能借 masked 后缀绕过敏感配置校验。
     *
     * @throws Exception 测试 JSON 解析失败
     */
    @Test
    void rejectsInvalidMaskedSecretSummaries() throws Exception {
        List<String> invalidConfigs = List.of(
                "{\"apiKeyMasked\":\"sk-live-secret\"}",
                "{\"apikeymasked\":\"sk-live-secret\"}",
                "{\"apiKeymasked\":\"sk-live-secret\"}",
                "{\"APIKEYMASKED\":\"sk-live-secret\"}",
                "{\"tokenmasked\":\"plain-token\"}",
                "{\"accessKeyMasked\":\"plain\"}",
                "{\"privateKeyMasked\":{\"value\":\"****\"}}",
                "{\"tokenMasked\":[\"****\"]}");

        for (String invalidConfig : invalidConfigs) {
            assertThatThrownBy(() -> service.updateConfig(
                            "model.active",
                            new UpdateUserConfigRequest(0, objectMapper.readTree(invalidConfig))))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
        verify(configMapper, never()).insert(any(UserConfigEntity.class));
    }

    /**
     * 验证 maxTokens 只有数值形式属于允许的 token 数量配置。
     *
     * @throws Exception 测试 JSON 解析失败
     */
    @Test
    void rejectsNonNumericMaxTokens() throws Exception {
        assertThatThrownBy(() -> service.updateConfig(
                        "writing.preferences",
                        new UpdateUserConfigRequest(0, objectMapper.readTree("{\"maxTokens\":\"4096\"}"))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(configMapper, never()).insert(any(UserConfigEntity.class));
    }

    /**
     * 验证 token 数量配置和明确脱敏的密钥摘要字段可以保存。
     *
     * @throws Exception 测试 JSON 解析失败
     */
    @Test
    void allowsMaxTokensAndExplicitMaskedKeySummaries() throws Exception {
        when(configMapper.selectList(any())).thenReturn(List.of());
        when(configMapper.insert(any(UserConfigEntity.class))).thenReturn(1);

        var writing = service.updateConfig(
                "writing.preferences",
                new UpdateUserConfigRequest(0, objectMapper.readTree("{\"maxTokens\":4096}")));
        var model = service.updateConfig(
                "model.active",
                new UpdateUserConfigRequest(0, objectMapper.readTree("{\"apiKeyMasked\":\"sk-****\"}")));
        var compactMasked = service.updateConfig(
                "model.active",
                new UpdateUserConfigRequest(0, objectMapper.readTree("{\"apikeymasked\":\"sk-****\"}")));

        assertThat(writing.version()).isEqualTo(1);
        assertThat(model.version()).isEqualTo(1);
        assertThat(compactMasked.version()).isEqualTo(1);
        verify(configMapper, times(3)).insert(any(UserConfigEntity.class));
    }

    /**
     * 验证读取历史配置时也不会返回敏感字段。
     */
    @Test
    void removesSensitiveFieldsFromStoredConfigResponse() {
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                601L,
                "model.active",
                "{\"provider\":\"local\",\"maxTokens\":4096,"
                        + "\"apiKeyMasked\":\"sk-****\","
                        + "\"apikeymasked\":\"sk-live-secret\","
                        + "\"apiKeymasked\":\"sk-live-secret\","
                        + "\"APIKEYMASKED\":\"sk-live-secret\","
                        + "\"tokenmasked\":\"plain-token\","
                        + "\"providers\":[{\"accessKey\":\"legacy-access\","
                        + "\"accessKeyMasked\":\"legacy-access-masked\","
                        + "\"privateKeyMasked\":{\"value\":\"****\"},"
                        + "\"tokenMasked\":[\"****\"],"
                        + "\"privateKey\":\"legacy-private\","
                        + "\"token\":\"legacy-token\","
                        + "\"tokens\":[\"legacy-token-list\"],"
                        + "\"clientSecret\":\"legacy-secret\","
                        + "\"clientSecrets\":[\"legacy-secret-list\"],"
                        + "\"password\":\"legacy-password\","
                        + "\"credential\":\"legacy-credential\"}]}",
                1)));

        var result = service.getConfig("model.active");

        assertThat(result.configValue().has("provider")).isTrue();
        assertThat(result.configValue().get("maxTokens").asInt()).isEqualTo(4096);
        assertThat(result.configValue().get("apiKeyConfigured").asBoolean()).isFalse();
        assertThat(result.configValue().has("apiKeyMasked")).isFalse();
        assertThat(result.configValue().has("apikeymasked")).isFalse();
        assertThat(result.configValue().has("apiKeymasked")).isFalse();
        assertThat(result.configValue().has("APIKEYMASKED")).isFalse();
        assertThat(result.configValue().has("tokenmasked")).isFalse();
        assertThat(result.configValue().toString()).doesNotContain("legacy-");
        assertThat(result.configValue().get("providers").get(0).isEmpty()).isTrue();
    }

    /**
     * 验证完整模型元数据只表示已配置，不进行网络探测。
     */
    @Test
    void reportsConfiguredModelAsNotTestedAndUnavailable() {
        configureStoredCredential();
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                601L,
                "model.active",
                "{\"provider\":\"deepseek\","
                        + "\"providerName\":\"DeepSeek\","
                        + "\"baseUrl\":\"https://api.deepseek.com\","
                        + "\"defaultModel\":\"deepseek-v4-flash\","
                        + "\"apiKey\":\"" + TEST_API_KEY + "\"}",
                1)));

        var result = service.getModelStatus();

        assertThat(result.configured()).isTrue();
        assertThat(result.available()).isFalse();
        assertThat(result.lastTestStatus()).isEqualTo("not_tested");
        assertThat(result.activeModel()).isEqualTo("deepseek-v4-flash");
        assertThat(result.configVersion()).isEqualTo(1);
    }

    /**
     * 验证讨论任务只能读取已连通的 DeepSeek 运行时配置。
     */
    @Test
    void returnsAvailableDeepSeekRuntimeConfig() {
        configureStoredCredential();
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                601L,
                "model.active",
                "{\"provider\":\"deepseek\","
                        + "\"providerName\":\"DeepSeek\","
                        + "\"baseUrl\":\"https://api.deepseek.com\","
                        + "\"defaultModel\":\"deepseek-v4-flash\","
                        + "\"apiKey\":\"" + TEST_API_KEY + "\","
                        + "\"lastTestStatus\":\"success\"}",
                1)));

        var result = service.requireAvailableModelConfig();

        assertThat(result.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(result.apiKey()).isEqualTo(TEST_API_KEY);
        assertThat(result.model()).isEqualTo("deepseek-v4-flash");
    }

    /**
     * 验证尚未连通测试的模型配置不能用于发起真实 AI 任务。
     */
    @Test
    void rejectsUnavailableDeepSeekRuntimeConfig() {
        configureStoredCredential();
        when(configMapper.selectList(any())).thenReturn(List.of(deepSeekConfig(1)));

        assertThatThrownBy(() -> service.requireAvailableModelConfig())
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
    }

    /**
     * 验证模型元数据不完整时报告未配置。
     */
    @Test
    void reportsIncompleteModelAsNotConfigured() {
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                601L,
                "model.active",
                "{\"provider\":\"deepseek\",\"defaultModel\":\"deepseek-v4-flash\"}",
                1)));

        var result = service.getModelStatus();

        assertThat(result.configured()).isFalse();
        assertThat(result.available()).isFalse();
        assertThat(result.lastTestStatus()).isEqualTo("not_tested");
    }

    /**
     * 验证未配置 Key 时不创建 Provider，也不访问网络。
     */
    @Test
    void rejectsConnectionTestWithoutApiKeyBeforeProviderCall() {
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                602L,
                "model.active",
                "{\"provider\":\"deepseek\",\"defaultModel\":\"deepseek-v4-flash\"}",
                2)));

        assertThatThrownBy(() -> service.testModelConnection(2))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(providerFactory, never()).create(any());
    }

    /**
     * 验证 Fake Provider 成功后持久化 success 并递增配置版本。
     */
    @Test
    void persistsSuccessfulConnectionTest() {
        configureStoredCredential();
        when(configMapper.selectList(any())).thenReturn(List.of(deepSeekConfig(2)));
        when(providerFactory.create(any())).thenReturn(provider);
        when(configMapper.update(any(), any())).thenReturn(1);

        var result = service.testModelConnection(2);

        assertThat(result.configured()).isTrue();
        assertThat(result.available()).isTrue();
        assertThat(result.lastTestStatus()).isEqualTo("success");
        assertThat(result.lastError()).isNull();
        assertThat(result.checkedAt()).isNotNull();
        assertThat(result.configVersion()).isEqualTo(3);
        verify(provider).testConnection();
    }

    /**
     * 验证可预期 Provider 失败只持久化安全中文错误并正常返回 failed 状态。
     */
    @Test
    void persistsSafeFailedConnectionTest() {
        configureStoredCredential();
        when(configMapper.selectList(any())).thenReturn(List.of(deepSeekConfig(2)));
        when(providerFactory.create(any())).thenReturn(provider);
        org.mockito.Mockito.doThrow(new LlmProviderException(LlmProviderError.AUTHENTICATION))
                .when(provider).testConnection();
        when(configMapper.update(any(), any())).thenReturn(1);

        var result = service.testModelConnection(2);

        assertThat(result.available()).isFalse();
        assertThat(result.lastTestStatus()).isEqualTo("failed");
        assertThat(result.lastError()).isEqualTo("DeepSeek 鉴权失败");
        assertThat(result.lastError()).doesNotContain(TEST_API_KEY);
    }

    /**
     * 验证远程请求期间配置发生变化时不会覆盖新配置。
     */
    @Test
    void rejectsConnectionTestWriteBackWhenConfigVersionChanged() {
        configureStoredCredential();
        when(configMapper.selectList(any())).thenReturn(List.of(deepSeekConfig(2)));
        when(providerFactory.create(any())).thenReturn(provider);
        when(configMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.testModelConnection(2))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CONFIG_VERSION_CONFLICT);
    }

    /**
     * 构造测试配置。
     *
     * @param id 配置 ID
     * @param key 配置键
     * @param value 配置 JSON
     * @param version 配置版本
     * @return 配置实体
     */
    private UserConfigEntity config(Long id, String key, String value, Integer version) {
        UserConfigEntity config = new UserConfigEntity();
        config.setId(id);
        config.setUserId("local-user");
        config.setConfigKey(key);
        config.setConfigValue(value);
        config.setVersion(version);
        config.setDeleted(0);
        return config;
    }

    private UserConfigEntity deepSeekConfig(int version) {
        return config(
                602L,
                "model.active",
                "{\"provider\":\"deepseek\","
                        + "\"providerName\":\"DeepSeek\","
                        + "\"baseUrl\":\"https://api.deepseek.com\","
                        + "\"defaultModel\":\"deepseek-v4-flash\","
                        + "\"lastTestStatus\":\"not_tested\"}",
                version);
    }

    /**
     * 验证包含 Key 的 API 请求对象字符串表示不会泄露请求内容。
     */
    @Test
    void masksApiKeyInUpdateRequestToString() {
        UpdateUserConfigRequest request = new UpdateUserConfigRequest(
                1,
                objectMapper.createObjectNode().put("nested", "private-value"),
                TEST_API_KEY,
                false);

        assertThat(request.toString())
                .contains("apiKey=****", "configValue=****")
                .doesNotContain(TEST_API_KEY, "private-value");
    }

    private void configureStoredCredential() {
        when(credentialService.summary(any()))
                .thenReturn(new CredentialSummary(true, "****-key", 1));
        org.mockito.Mockito.lenient()
                .when(credentialService.requirePlaintext(any()))
                .thenReturn(TEST_API_KEY);
    }

    /**
     * 并发执行两个请求并收集成功值或运行时异常。
     *
     * @param first 第一个请求
     * @param second 第二个请求
     * @return 两个请求结果
     * @throws Exception 等待并发任务失败
     */
    private List<Object> runConcurrently(Supplier<?> first, Supplier<?> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Object> firstResult = CompletableFuture.supplyAsync(() -> outcome(first), executor);
            CompletableFuture<Object> secondResult = CompletableFuture.supplyAsync(() -> outcome(second), executor);
            return List.of(
                    firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 将运行时异常转换为可断言的并发结果。
     *
     * @param action 待执行请求
     * @return 成功值或运行时异常
     */
    private Object outcome(Supplier<?> action) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            return exception;
        }
    }
}
