package com.dugnan.moqi.chapter.stream;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 表示已提交并等待执行的章节讨论回复任务。
 */
public record ConversationReplyTaskSubmittedEvent(Long taskId) {
}
