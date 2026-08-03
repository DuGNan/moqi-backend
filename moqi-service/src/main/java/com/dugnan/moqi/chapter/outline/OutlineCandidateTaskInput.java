package com.dugnan.moqi.chapter.outline;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 定义可恢复的大纲调整候选任务输入引用。
 */
public record OutlineCandidateTaskInput(
        Integer schemaVersion,
        String candidateType,
        Long conversationId,
        Long confirmedBriefId,
        Long baseOutlineId,
        Integer baseOutlineRevision,
        String instruction,
        String idempotencyKey) {

    /**
     * 兼容 V18 前保存的调整候选任务输入。
     */
    public OutlineCandidateTaskInput(
            Long conversationId,
            Long confirmedBriefId,
            Long baseOutlineId,
            Integer baseOutlineRevision,
            String instruction) {
        this(1, "adjustment", conversationId, confirmedBriefId, baseOutlineId, baseOutlineRevision, instruction, null);
    }
}
