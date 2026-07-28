package com.dugnan.moqi.chapter.stream;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 表示章节共识异步任务已在事务中创建。
 */
public record ChapterConsensusTaskSubmittedEvent(Long taskId) {
}
