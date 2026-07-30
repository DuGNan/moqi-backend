package com.dugnan.moqi.chapter.stream;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 表示章节大纲调整候选或其关联任务已更新的安全资源通知。
 */
public record OutlineCandidateEvent(
        String type,
        Long chapterId,
        Long aiTaskId,
        Long candidateId,
        String taskStatus,
        String candidateStatus,
        Long outlineId,
        Integer outlineRevision) {

    /**
     * 创建只含资源引用和状态的候选更新事件。
     *
     * @return 候选更新事件
     */
    public static OutlineCandidateEvent updated(
            Long chapterId,
            Long aiTaskId,
            Long candidateId,
            String taskStatus,
            String candidateStatus,
            Long outlineId,
            Integer outlineRevision) {
        return new OutlineCandidateEvent(
                "outline_candidate.updated", chapterId, aiTaskId, candidateId, taskStatus, candidateStatus,
                outlineId, outlineRevision);
    }
}
