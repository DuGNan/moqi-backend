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
            String errorCode,
            String errorMessage,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record AiTaskCanceled(
            Long taskId,
            String taskStatus,
            LocalDateTime gmtModified) {
    }
}
