package com.dugnan.moqi.config.service.impl;

import java.time.LocalDateTime;
import java.util.Arrays;
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

/**
 * @author dgn
 * @date 2026-07-18
 * @description 实现本地用户配置、敏感键保护和离线模型状态查询。
 */
@Service
public class UserConfigServiceImpl implements UserConfigService {

    private static final String LOCAL_USER = "local-user";
    private static final String MODEL_CONFIG_KEY = "model.active";
    private static final String NOT_TESTED = "not_tested";
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
    private static final Set<String> SENSITIVE_KEY_WORDS =
            Set.of(
                    "token", "tokens",
                    "secret", "secrets",
                    "password", "passwords",
                    "credential", "credentials");
    private static final Set<String> KEY_QUALIFIER_WORDS = Set.of("api", "access", "private");
    private static final Set<String> KEY_WORDS = Set.of("key", "keys");
    private static final Set<String> ALLOWED_NUMERIC_KEY_NAMES = Set.of("maxtokens");

    private final UserConfigMapper configMapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建用户配置服务。
     *
     * @param configMapper 用户配置数据访问对象
     * @param objectMapper JSON 解析器
     */
    public UserConfigServiceImpl(UserConfigMapper configMapper, ObjectMapper objectMapper) {
        this.configMapper = configMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public UserConfigDetail getConfig(String configKey) {
        String key = allowedKey(configKey);
        UserConfigEntity entity = findConfig(key);
        if (entity == null) {
            return new UserConfigDetail(
                    null,
                    LOCAL_USER,
                    key,
                    objectMapper.createObjectNode(),
                    0,
                    null);
        }
        return new UserConfigDetail(
                entity.getId(),
                entity.getUserId(),
                entity.getConfigKey(),
                sanitize(parse(entity.getConfigValue())),
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

        UserConfigEntity entity = findConfig(key);
        if (entity == null) {
            return createConfig(key, request.baseVersion(), configValue);
        }
        int currentVersion = version(entity);
        if (request.baseVersion() != currentVersion) {
            throw versionConflict();
        }

        int nextVersion = currentVersion + 1;
        LocalDateTime modifiedAt = LocalDateTime.now();
        UpdateWrapper<UserConfigEntity> update = new UpdateWrapper<UserConfigEntity>()
                .eq("id", entity.getId())
                .eq("user_id", LOCAL_USER)
                .eq("config_key", key)
                .eq("version", currentVersion)
                .eq("deleted", 0)
                .set("config_value", write(configValue))
                .set("version", nextVersion)
                .set("gmt_modified", modifiedAt);
        if (configMapper.update(null, update) != 1) {
            throw versionConflict();
        }
        return new UserConfigSaved(entity.getId(), key, nextVersion, modifiedAt);
    }

    @Override
    public ModelStatus getModelStatus() {
        UserConfigEntity entity = findConfig(MODEL_CONFIG_KEY);
        JsonNode configValue = entity == null
                ? objectMapper.createObjectNode()
                : sanitize(parse(entity.getConfigValue()));
        String provider = text(configValue, "provider");
        String providerName = text(configValue, "providerName");
        String baseUrl = text(configValue, "baseUrl");
        String activeModel = text(configValue, "defaultModel");
        boolean isConfigured = StringUtils.hasText(provider)
                && StringUtils.hasText(providerName)
                && StringUtils.hasText(baseUrl)
                && StringUtils.hasText(activeModel);
        return new ModelStatus(
                isConfigured,
                false,
                isConfigured ? provider : null,
                isConfigured ? providerName : null,
                isConfigured ? activeModel : null,
                NOT_TESTED,
                null,
                LocalDateTime.now());
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
        String[] words = keyWords(fieldName);
        if (words.length == 0 || isAllowedNumericKey(fieldName)) {
            return false;
        }
        return isSensitiveWords(words, words.length);
    }

    /**
     * 判断字段是否为敏感键的明确 masked 摘要形式。
     *
     * @param fieldName 字段名
     * @return 是否 masked 摘要字段
     */
    private boolean isMaskedSummaryKey(String fieldName) {
        String[] words = keyWords(fieldName);
        return words.length > 1
                && MASKED_KEY_SUFFIX.equals(words[words.length - 1])
                && isSensitiveWords(words, words.length - 1);
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
        String[] words = keyWords(fieldName);
        return ALLOWED_NUMERIC_KEY_NAMES.contains(normalizedKey(words, words.length));
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
     * 将字段名拆分为小写语义单词。
     *
     * @param fieldName 字段名
     * @return 字段名单词
     */
    private String[] keyWords(String fieldName) {
        String separated = fieldName
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (separated.isEmpty()) {
            return new String[0];
        }
        return separated.split(" +");
    }

    /**
     * 判断指定长度的字段名单词是否表达敏感凭据。
     *
     * @param words 字段名单词
     * @param length 参与判断的单词数量
     * @return 是否敏感
     */
    private boolean isSensitiveWords(String[] words, int length) {
        String normalized = normalizedKey(words, length);
        if (SENSITIVE_KEY_NAMES.contains(normalized)) {
            return true;
        }
        for (int index = 0; index < length; index++) {
            if (SENSITIVE_KEY_WORDS.contains(words[index])) {
                return true;
            }
            if (index + 1 < length
                    && KEY_WORDS.contains(words[index + 1])
                    && KEY_QUALIFIER_WORDS.contains(words[index])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 合并指定长度的字段名单词。
     *
     * @param words 字段名单词
     * @param length 合并数量
     * @return 规范化字段名
     */
    private String normalizedKey(String[] words, int length) {
        return String.join("", Arrays.copyOf(words, length));
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
}
