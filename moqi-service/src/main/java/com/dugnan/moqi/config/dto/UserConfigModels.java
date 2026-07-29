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

        /**
         * 保留不更新模型凭据的旧构造入口。
         *
         * @param baseVersion 基础配置版本
         * @param configValue 配置内容
         */
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

        /**
         * 保留不返回配置版本的旧构造入口。
         *
         * @param configured 是否已完成必要配置
         * @param available 是否可用于模型调用
         * @param provider 供应商标识
         * @param providerName 供应商显示名
         * @param activeModel 当前模型标识
         * @param lastTestStatus 最近连接测试状态
         * @param lastError 最近安全错误消息
         * @param checkedAt 最近检查时间
         */
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
