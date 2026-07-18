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

/**
 * @author dgn
 * @date 2026-07-18
 * @description 验证用户配置白名单、乐观锁、敏感键保护和模型状态规则。
 */
@ExtendWith(MockitoExtension.class)
class UserConfigServiceImplTest {

    @Mock
    private UserConfigMapper configMapper;

    private ObjectMapper objectMapper;
    private UserConfigServiceImpl service;

    /**
     * 初始化用户配置服务。
     */
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new UserConfigServiceImpl(configMapper, objectMapper);
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

        assertThat(writing.version()).isEqualTo(1);
        assertThat(model.version()).isEqualTo(1);
        verify(configMapper, times(2)).insert(any(UserConfigEntity.class));
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
        assertThat(result.configValue().get("apiKeyMasked").asText()).isEqualTo("sk-****");
        assertThat(result.configValue().toString()).doesNotContain("legacy-");
        assertThat(result.configValue().get("providers").get(0).isEmpty()).isTrue();
    }

    /**
     * 验证完整模型元数据只表示已配置，不进行网络探测。
     */
    @Test
    void reportsConfiguredModelAsNotTestedAndUnavailable() {
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                601L,
                "model.active",
                "{\"provider\":\"openai_compatible\","
                        + "\"providerName\":\"OpenAI Compatible\","
                        + "\"baseUrl\":\"https://api.example.com/v1\","
                        + "\"defaultModel\":\"gpt-4.1\"}",
                1)));

        var result = service.getModelStatus();

        assertThat(result.configured()).isTrue();
        assertThat(result.available()).isFalse();
        assertThat(result.lastTestStatus()).isEqualTo("not_tested");
        assertThat(result.activeModel()).isEqualTo("gpt-4.1");
    }

    /**
     * 验证模型元数据不完整时报告未配置。
     */
    @Test
    void reportsIncompleteModelAsNotConfigured() {
        when(configMapper.selectList(any())).thenReturn(List.of(config(
                601L,
                "model.active",
                "{\"provider\":\"openai_compatible\",\"defaultModel\":\"gpt-4.1\"}",
                1)));

        var result = service.getModelStatus();

        assertThat(result.configured()).isFalse();
        assertThat(result.available()).isFalse();
        assertThat(result.lastTestStatus()).isEqualTo("not_tested");
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
