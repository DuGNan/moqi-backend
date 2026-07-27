package com.dugnan.moqi.config.service.impl;

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.dto.UserConfigModels.ModelStatus;
import com.dugnan.moqi.config.dto.UserConfigModels.UpdateUserConfigRequest;
import com.dugnan.moqi.config.dto.UserConfigModels.UserConfigDetail;
import com.dugnan.moqi.config.dto.UserConfigModels.UserConfigSaved;
import com.dugnan.moqi.config.entity.UserConfigEntity;
import com.dugnan.moqi.config.mapper.UserConfigMapper;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.credential.CredentialIdentity;
import com.dugnan.moqi.credential.CredentialSecurityException;
import com.dugnan.moqi.credential.CredentialStateConflictException;
import com.dugnan.moqi.credential.CredentialSummary;
import com.dugnan.moqi.credential.LlmCredentialService;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 实现本地用户配置、敏感键保护和离线模型状态查询。
 */
@Service
public class UserConfigServiceImpl implements UserConfigService {

    private static final String LOCAL_USER = "local-user";
    private static final String MODEL_CONFIG_KEY = "model.active";
    private static final String DEEPSEEK_PROVIDER = "deepseek";
    private static final String DEEPSEEK_PROVIDER_NAME = "DeepSeek";
    private static final String API_KEY_CREDENTIAL_TYPE = "api_key";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private static final String NOT_TESTED = "not_tested";
    private static final Set<String> ALLOWED_MODELS = Set.of(DEFAULT_MODEL, "deepseek-v4-pro");
    private static final String MASKED_KEY_SUFFIX = "masked";
    private static final Pattern MASKED_SECRET_PATTERN = Pattern.compile(
            "^[A-Za-z0-9]{0,4}[-_:]?\\*{4,}[-_:]?[A-Za-z0-9]{0,4}$");
    private static final Set<String> ALLOWED_CONFIG_KEYS = Set.of(
            MODEL_CONFIG_KEY,
            "writing.preferences",
            "appearance.preferences",
            "sync.preferences");
    private static final Set<String> SENSITIVE_KEY_NAMES = Set.of(
            "apikey",
            "apikeys",
            "accesskey",
            "accesskeys",
            "privatekey",
            "privatekeys",
            "token",
            "tokens",
            "secret",
            "secrets",
            "password",
            "passwords",
            "credential",
            "credentials");
    private static final Set<String> SENSITIVE_KEY_FRAGMENTS = Set.of(
            "apikey",
            "accesskey",
            "privatekey",
            "token",
            "secret",
            "password",
            "credential");
    private static final Set<String> ALLOWED_NUMERIC_KEY_NAMES = Set.of("maxtokens");

    private final UserConfigMapper configMapper;
    private final ObjectMapper objectMapper;
    private final LlmProviderFactory providerFactory;
    private final LlmCredentialService credentialService;

    /**
     * 创建用户配置服务。
     *
     * @param configMapper 用户配置数据访问对象
     * @param objectMapper JSON 解析器
     */
    @Autowired
    public UserConfigServiceImpl(
            UserConfigMapper configMapper,
            ObjectMapper objectMapper,
            LlmProviderFactory providerFactory,
            LlmCredentialService credentialService) {
        this.configMapper = configMapper;
        this.objectMapper = objectMapper;
        this.providerFactory = providerFactory;
        this.credentialService = credentialService;
    }

    @Override
    public UserConfigDetail getConfig(String configKey) {
        String key = allowedKey(configKey);
        UserConfigEntity entity = findConfig(key);
        if (entity == null) {
            ObjectNode emptyValue = objectMapper.createObjectNode();
            if (MODEL_CONFIG_KEY.equals(key)) {
                emptyValue.put("apiKeyConfigured", false);
            }
            return new UserConfigDetail(
                    null,
                    LOCAL_USER,
                    key,
                    emptyValue,
                    0,
                    null);
        }
        JsonNode publicValue = MODEL_CONFIG_KEY.equals(key)
                ? sanitizeModelConfig(
                        parse(entity.getConfigValue()),
                        credentialService.summary(modelCredentialIdentity()))
                : sanitize(parse(entity.getConfigValue()));
        return new UserConfigDetail(
                entity.getId(),
                entity.getUserId(),
                entity.getConfigKey(),
                publicValue,
                version(entity),
                entity.getGmtModified());
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public UserConfigSaved updateConfig(String configKey, UpdateUserConfigRequest request) {
        String key = allowedKey(configKey);
        if (request == null || request.baseVersion() == null) {
            throw badRequest("baseVersion 不能为空");
        }
        JsonNode configValue = request.configValue();
        if (configValue == null || !configValue.isObject()) {
            throw badRequest("configValue 必须是 JSON 对象");
        }
        if (containsUnsafeConfigField(configValue)) {
            throw badRequest(
                    "configValue 包含敏感键、非法脱敏摘要或非法数值配置");
        }

        boolean hasModelSecretOperation = request.apiKey() != null || request.clearApiKey() != null;
        if (!MODEL_CONFIG_KEY.equals(key) && hasModelSecretOperation) {
            throw badRequest("只有 model.active 可以提交 apiKey 或 clearApiKey");
        }

        UserConfigEntity entity = findConfig(key);
        if (entity == null) {
            JsonNode valueToSave = MODEL_CONFIG_KEY.equals(key)
                    ? normalizeModelConfig(configValue, request)
                    : configValue;
            UserConfigSaved saved = createConfig(key, request.baseVersion(), valueToSave);
            applyCredentialOperation(key, request);
            return saved;
        }
        int currentVersion = version(entity);
        if (request.baseVersion() != currentVersion) {
            throw versionConflict();
        }
        JsonNode valueToSave = MODEL_CONFIG_KEY.equals(key)
                ? normalizeModelConfig(configValue, request)
                : configValue;

        int nextVersion = currentVersion + 1;
        LocalDateTime modifiedAt = LocalDateTime.now();
        UpdateWrapper<UserConfigEntity> update = new UpdateWrapper<UserConfigEntity>()
                .eq("id", entity.getId())
                .eq("user_id", LOCAL_USER)
                .eq("config_key", key)
                .eq("version", currentVersion)
                .eq("deleted", 0)
                .set("config_value", write(valueToSave))
                .set("version", nextVersion)
                .set("gmt_modified", modifiedAt);
        if (configMapper.update(null, update) != 1) {
            throw versionConflict();
        }
        applyCredentialOperation(key, request);
        return new UserConfigSaved(entity.getId(), key, nextVersion, modifiedAt);
    }

    @Override
    public ModelStatus getModelStatus() {
        UserConfigEntity entity = findConfig(MODEL_CONFIG_KEY);
        JsonNode configValue = entity == null
                ? objectMapper.createObjectNode()
                : parse(entity.getConfigValue());
        return toModelStatus(
                configValue,
                credentialService.summary(modelCredentialIdentity()),
                entity == null ? 0 : version(entity));
    }

    @Override
    public ModelStatus testModelConnection(Integer baseVersion) {
        if (baseVersion == null) {
            throw badRequest("baseVersion 不能为空");
        }
        UserConfigEntity entity = findConfig(MODEL_CONFIG_KEY);
        if (entity == null || version(entity) != baseVersion) {
            throw versionConflict();
        }
        JsonNode configValue = parse(entity.getConfigValue());
        String model = text(configValue, "defaultModel");
        CredentialSummary credential = credentialService.summary(modelCredentialIdentity());
        if (!credential.configured() || !ALLOWED_MODELS.contains(model)) {
            throw badRequest("DeepSeek 模型尚未配置");
        }

        String testStatus = "success";
        String safeError = null;
        try {
            providerFactory.create(runtimeConfig(configValue))
                    .testConnection();
        } catch (LlmProviderException exception) {
            testStatus = "failed";
            safeError = exception.getMessage();
        } catch (CredentialSecurityException exception) {
            throw modelCredentialUnavailable(exception);
        }

        LocalDateTime testedAt = LocalDateTime.now();
        ObjectNode testedConfig = (ObjectNode) configValue.deepCopy();
        testedConfig.put("lastTestStatus", testStatus);
        testedConfig.put("lastTestedAt", testedAt.toString());
        if (safeError == null) {
            testedConfig.remove("lastError");
        } else {
            testedConfig.put("lastError", safeError);
        }
        int nextVersion = baseVersion + 1;
        UpdateWrapper<UserConfigEntity> update = new UpdateWrapper<UserConfigEntity>()
                .eq("id", entity.getId())
                .eq("user_id", LOCAL_USER)
                .eq("config_key", MODEL_CONFIG_KEY)
                .eq("version", baseVersion)
                .eq("deleted", 0)
                .set("config_value", write(testedConfig))
                .set("version", nextVersion)
                .set("gmt_modified", testedAt);
        if (configMapper.update(null, update) != 1) {
            throw versionConflict();
        }
        return toModelStatus(testedConfig, credential, nextVersion);
    }

    @Override
    public LlmProviderRuntimeConfig requireAvailableModelConfig() {
        UserConfigEntity entity = findConfig(MODEL_CONFIG_KEY);
        if (entity == null) {
            throw modelUnavailable();
        }
        JsonNode configValue = parse(entity.getConfigValue());
        CredentialSummary credential = credentialService.summary(modelCredentialIdentity());
        ModelStatus modelStatus = toModelStatus(configValue, credential, version(entity));
        if (!modelStatus.available()) {
            throw modelUnavailable();
        }
        try {
            return runtimeConfig(configValue);
        } catch (CredentialSecurityException exception) {
            throw modelCredentialUnavailable(exception);
        }
    }

    private ModelStatus toModelStatus(
            JsonNode configValue,
            CredentialSummary credential,
            int configVersion) {
        String provider = text(configValue, "provider");
        String providerName = text(configValue, "providerName");
        String baseUrl = text(configValue, "baseUrl");
        String activeModel = text(configValue, "defaultModel");
        boolean isConfigured = credential.configured()
                && DEEPSEEK_PROVIDER.equals(provider)
                && DEEPSEEK_PROVIDER_NAME.equals(providerName)
                && DEEPSEEK_BASE_URL.equals(baseUrl)
                && ALLOWED_MODELS.contains(activeModel);
        String lastTestStatus = text(configValue, "lastTestStatus");
        if (!StringUtils.hasText(lastTestStatus)) {
            lastTestStatus = NOT_TESTED;
        }
        LocalDateTime checkedAt = parseDateTime(text(configValue, "lastTestedAt"));
        boolean isAvailable = isConfigured && "success".equals(lastTestStatus);
        return new ModelStatus(
                isConfigured,
                isAvailable,
                isConfigured ? provider : null,
                isConfigured ? providerName : null,
                isConfigured ? activeModel : null,
                lastTestStatus,
                text(configValue, "lastError"),
                checkedAt,
                configVersion);
    }

    /**
     * 规范化模型配置并应用密钥更新语义。
     */
    private JsonNode normalizeModelConfig(
            JsonNode submitted,
            UpdateUserConfigRequest request) {
        boolean isClearRequested = Boolean.TRUE.equals(request.clearApiKey());
        if (request.apiKey() != null && isClearRequested) {
            throw badRequest("apiKey 和 clearApiKey 不能同时提交");
        }
        ObjectNode normalized = (ObjectNode) sanitize(submitted);
        normalized.remove(Set.of(
                "apiKeyConfigured",
                "apiKeyMasked",
                "lastTestStatus",
                "lastError",
                "lastTestedAt"));
        String model = text(normalized, "defaultModel");
        if (!StringUtils.hasText(model)) {
            model = DEFAULT_MODEL;
        }
        if (!ALLOWED_MODELS.contains(model)) {
            throw badRequest("defaultModel 只允许 deepseek-v4-flash 或 deepseek-v4-pro");
        }
        normalized.put("provider", DEEPSEEK_PROVIDER);
        normalized.put("providerName", DEEPSEEK_PROVIDER_NAME);
        normalized.put("baseUrl", DEEPSEEK_BASE_URL);
        normalized.put("defaultModel", model);
        normalized.put("lastTestStatus", NOT_TESTED);
        return normalized;
    }

    /**
     * 构造不含明文密钥的模型配置响应。
     */
    private JsonNode sanitizeModelConfig(JsonNode stored, CredentialSummary credential) {
        ObjectNode sanitized = (ObjectNode) sanitize(stored);
        sanitized.put("apiKeyConfigured", credential.configured());
        if (credential.configured()) {
            sanitized.put("apiKeyMasked", credential.maskedValue());
        } else {
            sanitized.remove("apiKeyMasked");
        }
        return sanitized;
    }

    private void applyCredentialOperation(String key, UpdateUserConfigRequest request) {
        if (!MODEL_CONFIG_KEY.equals(key)) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(request.clearApiKey())) {
                credentialService.clear(modelCredentialIdentity());
            } else if (StringUtils.hasText(request.apiKey())) {
                credentialService.store(modelCredentialIdentity(), request.apiKey().trim());
            }
        } catch (CredentialStateConflictException exception) {
            throw versionConflict(exception);
        } catch (CredentialSecurityException exception) {
            throw modelCredentialUnavailable(exception);
        }
    }

    private LlmProviderRuntimeConfig runtimeConfig(JsonNode configValue) {
        return new LlmProviderRuntimeConfig(
                text(configValue, "provider"),
                text(configValue, "baseUrl"),
                credentialService.requirePlaintext(modelCredentialIdentity()),
                text(configValue, "defaultModel"));
    }

    private CredentialIdentity modelCredentialIdentity() {
        return new CredentialIdentity(
                LOCAL_USER,
                DEEPSEEK_PROVIDER,
                API_KEY_CREDENTIAL_TYPE);
    }

    private LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 创建缺失配置。
     *
     * @param key 配置键
     * @param baseVersion 基础版本
     * @param configValue 配置值
     * @return 保存结果
     */
    private UserConfigSaved createConfig(String key, int baseVersion, JsonNode configValue) {
        if (baseVersion != 0) {
            throw versionConflict();
        }
        UserConfigEntity entity = new UserConfigEntity();
        entity.setUserId(LOCAL_USER);
        entity.setConfigKey(key);
        entity.setConfigValue(write(configValue));
        entity.setDeleted(0);
        entity.setVersion(1);
        LocalDateTime createdAt = LocalDateTime.now();
        entity.setGmtCreate(createdAt);
        entity.setGmtModified(createdAt);
        try {
            if (configMapper.insert(entity) != 1) {
                throw versionConflict();
            }
        } catch (DuplicateKeyException exception) {
            throw versionConflict(exception);
        }
        return new UserConfigSaved(entity.getId(), key, 1, createdAt);
    }

    /**
     * 查询当前用户未删除配置。
     *
     * @param key 配置键
     * @return 配置实体，不存在时返回 null
     */
    private UserConfigEntity findConfig(String key) {
        return configMapper.selectList(
                        new LambdaQueryWrapper<UserConfigEntity>()
                                .eq(UserConfigEntity::getUserId, LOCAL_USER)
                                .eq(UserConfigEntity::getConfigKey, key)
                                .eq(UserConfigEntity::getDeleted, 0)
                                .orderByDesc(UserConfigEntity::getGmtModified)
                                .orderByDesc(UserConfigEntity::getId))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 校验配置键白名单。
     *
     * @param configKey 原始配置键
     * @return 合法配置键
     */
    private String allowedKey(String configKey) {
        String key = configKey == null ? null : configKey.trim();
        if (!ALLOWED_CONFIG_KEYS.contains(key)) {
            throw badRequest("configKey 不在允许范围内");
        }
        return key;
    }

    /**
     * 递归检查敏感字段名。
     *
     * @param node JSON 节点
     * @return 是否含敏感字段
     */
    private boolean containsUnsafeConfigField(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isAllowedNumericKey(field.getKey()) && !isValidAllowedNumericValue(field.getValue())) {
                    return true;
                }
                if (isMaskedSummaryKey(field.getKey()) && !isValidMaskedSummary(field.getValue())) {
                    return true;
                }
                if (!isMaskedSummaryKey(field.getKey()) && isSensitiveKey(field.getKey())) {
                    return true;
                }
                if (containsUnsafeConfigField(field.getValue())) {
                    return true;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsUnsafeConfigField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 清除历史配置中的敏感字段，保证读取响应不泄露明文。
     *
     * @param node 原始 JSON
     * @return 已清理 JSON
     */
    private JsonNode sanitize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sanitized = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isAllowedNumericKey(field.getKey())) {
                    if (isValidAllowedNumericValue(field.getValue())) {
                        sanitized.set(field.getKey(), field.getValue().deepCopy());
                    }
                } else if (isMaskedSummaryKey(field.getKey())) {
                    if (isValidMaskedSummary(field.getValue())) {
                        sanitized.set(field.getKey(), field.getValue().deepCopy());
                    }
                } else if (!isSensitiveKey(field.getKey())) {
                    sanitized.set(field.getKey(), sanitize(field.getValue()));
                }
            }
            return sanitized;
        }
        if (node.isArray()) {
            ArrayNode sanitized = objectMapper.createArrayNode();
            node.forEach(child -> sanitized.add(sanitize(child)));
            return sanitized;
        }
        return node.deepCopy();
    }

    /**
     * 判断字段名是否属于敏感键。
     *
     * @param fieldName 字段名
     * @return 是否敏感
     */
    private boolean isSensitiveKey(String fieldName) {
        String compactName = compactKeyName(fieldName);
        if (compactName.isEmpty() || isAllowedNumericKey(fieldName)) {
            return false;
        }
        return compactName.endsWith(MASKED_KEY_SUFFIX) || isSensitiveCompactKey(compactName);
    }

    /**
     * 判断字段是否为敏感键的明确 masked 摘要形式。
     *
     * @param fieldName 字段名
     * @return 是否 masked 摘要字段
     */
    private boolean isMaskedSummaryKey(String fieldName) {
        String compactName = compactKeyName(fieldName);
        if (!compactName.endsWith(MASKED_KEY_SUFFIX)) {
            return false;
        }
        String baseName = compactName.substring(0, compactName.length() - MASKED_KEY_SUFFIX.length());
        return isSensitiveCompactKey(baseName);
    }

    /**
     * 校验 masked 摘要值只暴露少量首尾字符并包含明确掩码。
     *
     * @param value 摘要值
     * @return 是否合法
     */
    private boolean isValidMaskedSummary(JsonNode value) {
        return value.isTextual() && MASKED_SECRET_PATTERN.matcher(value.asText()).matches();
    }

    /**
     * 判断字段是否为允许的数值配置。
     *
     * @param fieldName 字段名
     * @return 是否允许
     */
    private boolean isAllowedNumericKey(String fieldName) {
        return ALLOWED_NUMERIC_KEY_NAMES.contains(compactKeyName(fieldName));
    }

    /**
     * 校验白名单数值配置为正整数。
     *
     * @param value 配置值
     * @return 是否合法
     */
    private boolean isValidAllowedNumericValue(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0;
    }

    /**
     * 将字段名规范为仅含小写字母和数字的紧凑形式。
     *
     * @param fieldName 字段名
     * @return 紧凑字段名
     */
    private String compactKeyName(String fieldName) {
        return fieldName
                .replaceAll("[^A-Za-z0-9]+", "")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 判断紧凑字段名是否表达敏感凭据。
     *
     * @param compactName 紧凑字段名
     * @return 是否敏感
     */
    private boolean isSensitiveCompactKey(String compactName) {
        if (compactName.isEmpty() || ALLOWED_NUMERIC_KEY_NAMES.contains(compactName)) {
            return false;
        }
        if (SENSITIVE_KEY_NAMES.contains(compactName)) {
            return true;
        }
        for (String fragment : SENSITIVE_KEY_FRAGMENTS) {
            if (compactName.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析配置 JSON。
     *
     * @param value JSON 文本
     * @return JSON 节点
     */
    private JsonNode parse(String value) {
        if (!StringUtils.hasText(value)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("用户配置 JSON 数据格式非法", exception);
        }
    }

    /**
     * 序列化配置 JSON。
     *
     * @param value JSON 节点
     * @return JSON 文本
     */
    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("用户配置 JSON 序列化失败", exception);
        }
    }

    /**
     * 读取文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 文本值
     */
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText().trim();
    }

    /**
     * 获取配置版本。
     *
     * @param entity 配置实体
     * @return 非空版本
     */
    private int version(UserConfigEntity entity) {
        return entity.getVersion() == null ? 0 : entity.getVersion();
    }

    /**
     * 创建参数错误异常。
     *
     * @param message 错误消息
     * @return 参数错误异常
     */
    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 创建配置版本冲突异常。
     *
     * @return 版本冲突异常
     */
    private BusinessException versionConflict() {
        return new BusinessException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置已被更新，请刷新后重试");
    }

    /**
     * 创建保留数据库竞争原因的配置版本冲突异常。
     *
     * @param cause 唯一键竞争异常
     * @return 版本冲突异常
     */
    private BusinessException versionConflict(Throwable cause) {
        return new BusinessException(
                ErrorCode.CONFIG_VERSION_CONFLICT,
                "配置已被更新，请刷新后重试",
                cause);
    }

    private BusinessException modelUnavailable() {
        return new BusinessException(
                ErrorCode.MODEL_UNAVAILABLE,
                "DeepSeek 模型尚未配置完成或未通过连通测试");
    }

    private BusinessException modelCredentialUnavailable(Throwable cause) {
        return new BusinessException(
                ErrorCode.MODEL_UNAVAILABLE,
                "模型凭据安全配置不可用，请联系管理员",
                cause);
    }
}
