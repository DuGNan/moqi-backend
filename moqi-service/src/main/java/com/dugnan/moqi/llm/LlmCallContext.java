package com.dugnan.moqi.llm;

import org.springframework.util.StringUtils;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 显式描述一次模型调用可安全持久化的业务归属和版本引用。
 */
public record LlmCallContext(
        String userId,
        Long workId,
        Long chapterId,
        Long aiTaskId,
        Long conversationId,
        Long generationSceneId,
        Long agentRunId,
        Long agentStepId,
        String workflowType,
        String operationType,
        String logicalCallId,
        String promptTemplateVersion,
        String sourceFingerprint,
        String replyMode,
        String replyDepth,
        String replyScopeSummary,
        String controlSource,
        String policyVersion) {

    private static final String LOCAL_USER = "local-user";

    /**
     * 创建只包含必需字段的上下文构建器。
     *
     * @param workflowType 工作流类型
     * @param operationType 操作类型
     * @return 上下文构建器
     */
    public static Builder builder(String workflowType, String operationType) {
        return new Builder(workflowType, operationType);
    }

    /**
     * @author dgn
     * @date 2026-08-04
     * @description 分步收集模型调用可持久化白名单上下文。
     *
     * 构建安全模型调用上下文。
     */
    public static final class Builder {

        private final String workflowType;
        private final String operationType;
        private String userId = LOCAL_USER;
        private Long workId;
        private Long chapterId;
        private Long aiTaskId;
        private Long conversationId;
        private Long generationSceneId;
        private Long agentRunId;
        private Long agentStepId;
        private String logicalCallId;
        private String promptTemplateVersion = "unspecified";
        private String sourceFingerprint = "unspecified";
        private String replyMode;
        private String replyDepth;
        private String replyScopeSummary;
        private String controlSource;
        private String policyVersion;

        private Builder(String workflowType, String operationType) {
            if (!StringUtils.hasText(workflowType) || !StringUtils.hasText(operationType)) {
                throw new IllegalArgumentException("workflowType 和 operationType 不能为空");
            }
            this.workflowType = truncate(workflowType, 64);
            this.operationType = truncate(operationType, 64);
        }

        public Builder userId(String value) {
            this.userId = value;
            return this;
        }

        public Builder workId(Long value) {
            this.workId = value;
            return this;
        }

        public Builder chapterId(Long value) {
            this.chapterId = value;
            return this;
        }

        public Builder aiTaskId(Long value) {
            this.aiTaskId = value;
            return this;
        }

        public Builder conversationId(Long value) {
            this.conversationId = value;
            return this;
        }

        public Builder generationSceneId(Long value) {
            this.generationSceneId = value;
            return this;
        }

        public Builder agentRunId(Long value) {
            this.agentRunId = value;
            return this;
        }

        public Builder agentStepId(Long value) {
            this.agentStepId = value;
            return this;
        }

        public Builder logicalCallId(String value) {
            this.logicalCallId = value;
            return this;
        }

        public Builder promptTemplateVersion(String value) {
            this.promptTemplateVersion = value;
            return this;
        }

        public Builder sourceFingerprint(String value) {
            this.sourceFingerprint = value;
            return this;
        }

        public Builder replyPolicy(
                String mode,
                String depth,
                String scopeSummary,
                String source,
                String version) {
            this.replyMode = mode;
            this.replyDepth = depth;
            this.replyScopeSummary = scopeSummary;
            this.controlSource = source;
            this.policyVersion = version;
            return this;
        }

        public LlmCallContext build() {
            String safeLogicalId = StringUtils.hasText(logicalCallId)
                    ? logicalCallId.trim()
                    : defaultLogicalCallId();
            return new LlmCallContext(
                    StringUtils.hasText(userId) ? truncate(userId, 64) : LOCAL_USER,
                    workId,
                    chapterId,
                    aiTaskId,
                    conversationId,
                    generationSceneId,
                    agentRunId,
                    agentStepId,
                    workflowType,
                    operationType,
                    truncate(safeLogicalId, 128),
                    safe(promptTemplateVersion, 64),
                    safe(sourceFingerprint, 128),
                    truncate(replyMode, 32),
                    truncate(replyDepth, 32),
                    truncate(replyScopeSummary, 500),
                    truncate(controlSource, 32),
                    truncate(policyVersion, 64));
        }

        private String defaultLogicalCallId() {
            if (agentStepId != null) {
                return "agent-step:" + agentStepId + ":" + operationType;
            }
            if (aiTaskId != null) {
                return "ai-task:" + aiTaskId + ":" + operationType;
            }
            return workflowType + ":" + operationType + ":" + System.nanoTime();
        }

        private String safe(String value, int limit) {
            return StringUtils.hasText(value) ? truncate(value, limit) : "unspecified";
        }

        private String truncate(String value, int limit) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
        }
    }
}
