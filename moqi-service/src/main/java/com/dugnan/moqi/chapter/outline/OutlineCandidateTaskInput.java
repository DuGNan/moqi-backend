package com.dugnan.moqi.chapter.outline;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 定义可恢复的大纲调整候选任务输入引用。
 */
public record OutlineCandidateTaskInput(
        Long conversationId,
        Long confirmedBriefId,
        Long baseOutlineId,
        Integer baseOutlineRevision,
        String instruction) {
}
