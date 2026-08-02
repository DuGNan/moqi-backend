package com.dugnan.moqi.task.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskCanceled;
import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskDetail;
import com.dugnan.moqi.task.service.AiTaskService;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 提供 AI 任务查询与取消 HTTP 接口。
 */
@RestController
@RequestMapping("/api/ai-tasks")
public class AiTaskController {

    private final AiTaskService aiTaskService;

    /**
     * 创建 AI 任务控制器。
     *
     * @param aiTaskService AI 任务服务
     */
    public AiTaskController(AiTaskService aiTaskService) {
        this.aiTaskService = aiTaskService;
    }

    /**
     * 查询 AI 任务。
     *
     * @param taskId 任务 ID
     * @return 任务详情响应
     */
    @GetMapping("/{taskId}")
    public ApiResponse<AiTaskDetail> task(@PathVariable Long taskId) {
        return ApiResponse.success(aiTaskService.getTask(taskId));
    }

    /**
     * 幂等取消 AI 任务。
     *
     * @param taskId 任务 ID
     * @return 取消结果响应
     */
    @PostMapping("/{taskId}/cancel")
    public ApiResponse<AiTaskCanceled> cancel(@PathVariable Long taskId) {
        return ApiResponse.success(aiTaskService.cancelTask(taskId));
    }

    /**
     * 重新投递失败的章节讨论回复任务。
     *
     * @param taskId AI 任务 ID
     * @return 重试后的任务详情
     */
    @PostMapping("/{taskId}/retry")
    public ApiResponse<AiTaskDetail> retry(@PathVariable Long taskId) {
        return ApiResponse.success(aiTaskService.retryTask(taskId));
    }
}
