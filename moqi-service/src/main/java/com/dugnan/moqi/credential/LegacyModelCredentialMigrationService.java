package com.dugnan.moqi.credential;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.config.entity.UserConfigEntity;
import com.dugnan.moqi.config.mapper.UserConfigMapper;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 在单事务内迁移 model.active 旧明文 Key 并执行 CAS 清理。
 */
@Service
public class LegacyModelCredentialMigrationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LegacyModelCredentialMigrationService.class);
    private static final String MODEL_CONFIG_KEY = "model.active";
    private static final String DEEPSEEK_PROVIDER = "deepseek";
    private static final String API_KEY_CREDENTIAL_TYPE = "api_key";

    private final UserConfigMapper configMapper;
    private final LlmCredentialService credentialService;
    private final ObjectMapper objectMapper;

    /**
     * 创建旧模型凭据迁移服务。
     *
     * @param configMapper 用户配置数据访问对象
     * @param credentialService LLM 凭据服务
     * @param objectMapper JSON 映射器
     */
    public LegacyModelCredentialMigrationService(
            UserConfigMapper configMapper,
            LlmCredentialService credentialService,
            ObjectMapper objectMapper) {
        this.configMapper = configMapper;
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等迁移旧配置中的模型明文凭据并执行 CAS 清理。
     *
     * @return 成功迁移的配置数量
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public int migrate() {
        List<UserConfigEntity> configs = configMapper.selectList(
                new LambdaQueryWrapper<UserConfigEntity>()
                        .eq(UserConfigEntity::getConfigKey, MODEL_CONFIG_KEY)
                        .eq(UserConfigEntity::getDeleted, 0)
                        .orderByAsc(UserConfigEntity::getId));
        int migrated = 0;
        for (UserConfigEntity config : configs) {
            ObjectNode stored = parseObject(config);
            JsonNode apiKeyNode = stored.get("apiKey");
            if (apiKeyNode == null) {
                continue;
            }
            if (!apiKeyNode.isTextual() || !StringUtils.hasText(apiKeyNode.asText())) {
                throw new IllegalStateException("model.active 存在无法安全迁移的旧凭据字段");
            }
            CredentialIdentity identity = new CredentialIdentity(
                    config.getUserId(),
                    provider(stored),
                    API_KEY_CREDENTIAL_TYPE);
            credentialService.storeLegacyIfAbsent(identity, apiKeyNode.asText().trim());
            stored.remove("apiKey");
            updateConfig(config, stored);
            migrated++;
            LOGGER.info("已迁移旧模型凭据，configId={}", config.getId());
        }
        return migrated;
    }

    private ObjectNode parseObject(UserConfigEntity config) {
        try {
            JsonNode parsed = objectMapper.readTree(config.getConfigValue());
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalStateException("model.active 旧配置不是 JSON 对象");
            }
            return (ObjectNode) parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "model.active 旧配置 JSON 无法安全迁移，configId=" + config.getId(),
                    exception);
        }
    }

    private String provider(ObjectNode stored) {
        JsonNode provider = stored.get("provider");
        if (provider == null || !provider.isTextual() || !StringUtils.hasText(provider.asText())) {
            return DEEPSEEK_PROVIDER;
        }
        return provider.asText().trim();
    }

    private void updateConfig(UserConfigEntity config, ObjectNode stored) {
        int currentVersion = config.getVersion() == null ? 0 : config.getVersion();
        UpdateWrapper<UserConfigEntity> update = new UpdateWrapper<UserConfigEntity>()
                .eq("id", config.getId())
                .eq("user_id", config.getUserId())
                .eq("config_key", MODEL_CONFIG_KEY)
                .eq("deleted", 0)
                .eq("version", currentVersion)
                .set("config_value", write(stored))
                .set("version", currentVersion + 1)
                .set("gmt_modified", LocalDateTime.now());
        if (configMapper.update(null, update) != 1) {
            throw new CredentialStateConflictException();
        }
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("model.active 迁移结果无法序列化", exception);
        }
    }
}
