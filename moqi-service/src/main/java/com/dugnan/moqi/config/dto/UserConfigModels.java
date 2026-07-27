package com.dugnan.moqi.config.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 集中定义用户配置与模型状态接口模型。
 */
public final class UserConfigModels {

    /**
     * 禁止实例化模型容器。
     */
    private UserConfigModels() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UserConfigDetail(
            Long id,
            String userId,
            String configKey,
            JsonNode configValue,
            Integer version,
            LocalDateTime gmtModified) {
    }

    public record UpdateUserConfigRequest(
            Integer baseVersion,
            JsonNode configValue,
            String apiKey,
            Boolean clearApiKey) {

        public UpdateUserConfigRequest(Integer baseVersion, JsonNode configValue) {
            this(baseVersion, configValue, null, null);
        }

        @Override
        public String toString() {
            return "UpdateUserConfigRequest[baseVersion=" + baseVersion
                    + ", configValue=****, apiKey=****, clearApiKey=" + clearApiKey + "]";
        }
    }

    public record UserConfigSaved(
            Long id,
            String configKey,
            Integer version,
            LocalDateTime gmtModified) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ModelStatus(
            boolean configured,
            boolean available,
            String provider,
            String providerName,
            String activeModel,
            String lastTestStatus,
            String lastError,
            LocalDateTime checkedAt,
            Integer configVersion) {

        public ModelStatus(
                boolean configured,
                boolean available,
                String provider,
                String providerName,
                String activeModel,
                String lastTestStatus,
                String lastError,
                LocalDateTime checkedAt) {
            this(
                    configured,
                    available,
                    provider,
                    providerName,
                    activeModel,
                    lastTestStatus,
                    lastError,
                    checkedAt,
                    0);
        }
    }

    public record TestModelConnectionRequest(Integer baseVersion) {
    }
}
