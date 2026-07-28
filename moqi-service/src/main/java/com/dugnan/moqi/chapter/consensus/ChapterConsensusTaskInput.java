package com.dugnan.moqi.chapter.consensus;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 定义共识收束任务可恢复的最小 ID 引用。
 */
public record ChapterConsensusTaskInput(
        Long conversationId,
        Long baseBriefId,
        Long currentMessageId) {
}
