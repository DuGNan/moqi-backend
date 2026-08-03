package com.dugnan.moqi.chapter.stream;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 表示章节 Brief 资源已更新的安全通知。
 */
public record ChapterBriefEvent(
        String type,
        Long chapterId,
        Long taskId,
        Long briefId,
        String briefStatus,
        Integer schemaVersion,
        String triggerSource) {

    /**
     * 创建草稿资源更新事件。
     *
     * @param chapterId 章节 ID
     * @param taskId 任务 ID
     * @param briefId Brief ID
     * @return Brief 更新事件
     */
    public static ChapterBriefEvent draftUpdated(Long chapterId, Long taskId, Long briefId) {
        return new ChapterBriefEvent("brief.updated", chapterId, taskId, briefId, "draft", 1, null);
    }

    /** 创建包含来源的草稿资源更新事件。 */
    public static ChapterBriefEvent draftUpdated(Long chapterId, Long taskId, Long briefId, String triggerSource) {
        return new ChapterBriefEvent("brief.updated", chapterId, taskId, briefId, "draft", 1, triggerSource);
    }
}
