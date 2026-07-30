package com.dugnan.moqi.task.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

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
            String errorCode,
            String errorMessage,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {

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
                    errorCode,
                    errorMessage,
                    gmtCreate,
                    gmtModified);
        }
    }

    public record AiTaskCanceled(
            Long taskId,
            String taskStatus,
            LocalDateTime gmtModified) {
    }
}
