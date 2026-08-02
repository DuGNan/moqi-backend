package com.dugnan.moqi.task.service;

import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskCanceled;
import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskDetail;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 定义 AI 任务查询与取消能力。
 */
public interface AiTaskService {

    /**
     * 查询 AI 任务。
     *
     * @param taskId 任务 ID
     * @return 任务详情
     */
    AiTaskDetail getTask(Long taskId);

    /**
     * 幂等取消 AI 任务。
     *
     * @param taskId 任务 ID
     * @return 取消结果
     */
    AiTaskCanceled cancelTask(Long taskId);

    /**
     * 重试失败的章节讨论回复任务。
     *
     * @param taskId AI 任务 ID
     * @return 重试后的任务详情
     */
    AiTaskDetail retryTask(Long taskId);
}
