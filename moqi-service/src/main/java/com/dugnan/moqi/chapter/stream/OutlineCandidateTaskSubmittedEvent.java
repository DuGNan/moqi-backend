package com.dugnan.moqi.chapter.stream;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 表示章节大纲调整候选任务已在事务中创建。
 */
public record OutlineCandidateTaskSubmittedEvent(Long taskId) {
}
