package com.dugnan.moqi.task.event;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 在运行中 AI 任务提交取消后通知内部执行控制边界。
 */
public record AiTaskCancellationSignal(Long taskId) {
}
