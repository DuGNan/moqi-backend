package com.dugnan.moqi.task.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.dugnan.moqi.chapter.policy.ReplyScope;
import com.dugnan.moqi.common.api.PublicFailure;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 集中定义 AI 任务查询与取消接口模型。
 */
public final class AiTaskModels {

    /**
     * 禁止实例化模型容器。
     */
    private AiTaskModels() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AiTaskDetail(
            Long id,
            String taskType,
            String taskStatus,
            Long workId,
            Long chapterId,
            Long resultMessageId,
            Long resultGenerationId,
            Long resultBriefId,
            Long resultOutlineCandidateId,
            Long agentRunId,
            Long retryOfTaskId,
            EffectiveReplyPolicy effectiveReplyPolicy,
            String errorCode,
            String errorMessage,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified,
            PublicFailure failure) {

        public AiTaskDetail(
                Long id,
                String taskType,
                String taskStatus,
                Long workId,
                Long chapterId,
                Long resultMessageId,
                Long resultGenerationId,
                Long resultBriefId,
                Long resultOutlineCandidateId,
                Long agentRunId,
                Long retryOfTaskId,
                EffectiveReplyPolicy effectiveReplyPolicy,
                String errorCode,
                String errorMessage,
                LocalDateTime gmtCreate,
                LocalDateTime gmtModified) {
            this(id, taskType, taskStatus, workId, chapterId, resultMessageId, resultGenerationId,
                    resultBriefId, resultOutlineCandidateId, agentRunId, retryOfTaskId, effectiveReplyPolicy,
                    errorCode, errorMessage, gmtCreate, gmtModified, null);
        }

        /**
         * 保留不含 Brief 结果引用的旧构造入口。
         *
         * @param id 任务 ID
         * @param taskType 任务类型
         * @param taskStatus 任务状态
         * @param workId 作品 ID
         * @param chapterId 章节 ID
         * @param resultMessageId 结果消息 ID
         * @param resultGenerationId 结果生成记录 ID
         * @param errorCode 错误码
         * @param errorMessage 错误消息
         * @param gmtCreate 创建时间
         * @param gmtModified 修改时间
         */
        public AiTaskDetail(
                Long id,
                String taskType,
                String taskStatus,
                Long workId,
                Long chapterId,
                Long resultMessageId,
                Long resultGenerationId,
                String errorCode,
                String errorMessage,
                LocalDateTime gmtCreate,
                LocalDateTime gmtModified) {
            this(
                    id,
                    taskType,
                    taskStatus,
                    workId,
                    chapterId,
                    resultMessageId,
                    resultGenerationId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    errorCode,
                    errorMessage,
                    gmtCreate,
                    gmtModified,
                    null);
        }

        /**
         * 保留不含有效回复策略的既有完整构造入口。
         */
        public AiTaskDetail(
                Long id,
                String taskType,
                String taskStatus,
                Long workId,
                Long chapterId,
                Long resultMessageId,
                Long resultGenerationId,
                Long resultBriefId,
                Long resultOutlineCandidateId,
                Long agentRunId,
                String errorCode,
                String errorMessage,
                LocalDateTime gmtCreate,
                LocalDateTime gmtModified) {
            this(id, taskType, taskStatus, workId, chapterId, resultMessageId, resultGenerationId,
                    resultBriefId, resultOutlineCandidateId, agentRunId, null, null,
                    errorCode, errorMessage, gmtCreate, gmtModified, null);
        }

        /**
         * 保留不含重试来源的有效回复策略构造入口。
         */
        public AiTaskDetail(
                Long id,
                String taskType,
                String taskStatus,
                Long workId,
                Long chapterId,
                Long resultMessageId,
                Long resultGenerationId,
                Long resultBriefId,
                Long resultOutlineCandidateId,
                Long agentRunId,
                EffectiveReplyPolicy effectiveReplyPolicy,
                String errorCode,
                String errorMessage,
                LocalDateTime gmtCreate,
                LocalDateTime gmtModified) {
            this(id, taskType, taskStatus, workId, chapterId, resultMessageId, resultGenerationId,
                    resultBriefId, resultOutlineCandidateId, agentRunId, null, effectiveReplyPolicy,
                    errorCode, errorMessage, gmtCreate, gmtModified, null);
        }
    }

    /**
     * conversation_reply 对前端公开的最终有效策略。
     *
     * @param replyMode 回复模式
     * @param replyDepth 回复深度
     * @param replyScope 本轮推进范围
     * @param controlSource 控制来源
     * @param policyVersion 策略版本
     * @param convergenceApplied 是否应用收敛反馈
     */
    public record EffectiveReplyPolicy(
            String replyMode,
            String replyDepth,
            ReplyScope replyScope,
            String controlSource,
            String policyVersion,
            boolean convergenceApplied) {
    }

    public record AiTaskCanceled(
            Long taskId,
            String taskStatus,
            LocalDateTime gmtModified) {
    }
}
