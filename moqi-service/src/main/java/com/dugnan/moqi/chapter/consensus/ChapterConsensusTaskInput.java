package com.dugnan.moqi.chapter.consensus;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 定义共识收束任务可恢复的最小 ID 引用。
 */
public record ChapterConsensusTaskInput(
        Long conversationId,
        Long baseBriefId,
        Long currentMessageId,
        String triggerSource,
        Long lastMessageId,
        String evaluatorVersion,
        String idempotencyKey,
        java.util.List<Long> evidenceMessageIds,
        java.util.List<String> reasonCodes) {

    public ChapterConsensusTaskInput(Long conversationId, Long baseBriefId, Long currentMessageId) {
        this(conversationId, baseBriefId, currentMessageId, "manual", null, null, null, java.util.List.of(), java.util.List.of());
    }
}
