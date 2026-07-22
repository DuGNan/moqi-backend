package com.dugnan.moqi.chapter.stream;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 表示应推送给指定章节订阅者的讨论回复事件。
 */
public record ChapterReplyEvent(
        String type,
        Long chapterId,
        Long taskId,
        Long messageId,
        String text,
        String errorCode,
        String errorMessage) {

    public static ChapterReplyEvent started(Long chapterId, Long taskId) {
        return new ChapterReplyEvent("reply.started", chapterId, taskId, null, null, null, null);
    }

    public static ChapterReplyEvent delta(Long chapterId, Long taskId, String text) {
        return new ChapterReplyEvent("reply.delta", chapterId, taskId, null, text, null, null);
    }

    public static ChapterReplyEvent completed(Long chapterId, Long taskId, Long messageId) {
        return new ChapterReplyEvent("reply.completed", chapterId, taskId, messageId, null, null, null);
    }

    public static ChapterReplyEvent failed(Long chapterId, Long taskId, String errorCode, String errorMessage) {
        return new ChapterReplyEvent("reply.failed", chapterId, taskId, null, null, errorCode, errorMessage);
    }

    public static ChapterReplyEvent canceled(Long chapterId, Long taskId) {
        return new ChapterReplyEvent("reply.canceled", chapterId, taskId, null, null, null, null);
    }
}
