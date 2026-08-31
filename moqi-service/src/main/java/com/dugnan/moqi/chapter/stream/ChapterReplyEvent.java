package com.dugnan.moqi.chapter.stream;

import com.dugnan.moqi.common.api.PublicFailure;
import com.dugnan.moqi.common.api.PublicFailureFactory;

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
        String errorMessage,
        PublicFailure failure,
        Long conversationId) {

    public ChapterReplyEvent(
            String type,
            Long chapterId,
            Long taskId,
            Long messageId,
            String text,
            String errorCode,
            String errorMessage) {
        this(type, chapterId, taskId, messageId, text, errorCode, errorMessage, null, null);
    }

    public ChapterReplyEvent(
            String type,
            Long chapterId,
            Long taskId,
            Long messageId,
            String text,
            String errorCode,
            String errorMessage,
            PublicFailure failure) {
        this(type, chapterId, taskId, messageId, text, errorCode, errorMessage, failure, null);
    }

    /**
     * 创建回复开始事件。
     *
     * @param chapterId 章节 ID
     * @param taskId 任务 ID
     * @return 回复开始事件
     */
    public static ChapterReplyEvent started(Long chapterId, Long taskId) {
        return new ChapterReplyEvent("reply.started", chapterId, taskId, null, null, null, null);
    }

    public static ChapterReplyEvent started(Long chapterId, Long taskId, Long conversationId) {
        return new ChapterReplyEvent(
                "reply.started", chapterId, taskId, null, null, null, null, null, conversationId);
    }

    /**
     * 创建回复增量事件。
     *
     * @param chapterId 章节 ID
     * @param taskId 任务 ID
     * @param text 新增文本
     * @return 回复增量事件
     */
    public static ChapterReplyEvent delta(Long chapterId, Long taskId, String text) {
        return new ChapterReplyEvent("reply.delta", chapterId, taskId, null, text, null, null);
    }

    public static ChapterReplyEvent delta(Long chapterId, Long taskId, Long conversationId, String text) {
        return new ChapterReplyEvent(
                "reply.delta", chapterId, taskId, null, text, null, null, null, conversationId);
    }

    /**
     * 创建回复完成事件。
     *
     * @param chapterId 章节 ID
     * @param taskId 任务 ID
     * @param messageId 已持久化消息 ID
     * @return 回复完成事件
     */
    public static ChapterReplyEvent completed(Long chapterId, Long taskId, Long messageId) {
        return new ChapterReplyEvent("reply.completed", chapterId, taskId, messageId, null, null, null);
    }

    public static ChapterReplyEvent completed(
            Long chapterId,
            Long taskId,
            Long conversationId,
            Long messageId) {
        return new ChapterReplyEvent(
                "reply.completed", chapterId, taskId, messageId, null, null, null, null, conversationId);
    }

    /**
     * 创建回复正在停止事件。
     *
     * @param chapterId 章节 ID
     * @param taskId 任务 ID
     * @return 回复正在停止事件
     */
    public static ChapterReplyEvent canceling(Long chapterId, Long taskId) {
        return new ChapterReplyEvent("reply.canceling", chapterId, taskId, null, null, null, null);
    }

    /**
     * 创建回复失败事件。
     *
     * @param chapterId 章节 ID
     * @param taskId 任务 ID
     * @param errorCode 安全错误码
     * @param errorMessage 安全错误消息
     * @return 回复失败事件
     */
    public static ChapterReplyEvent failed(Long chapterId, Long taskId, String errorCode, String errorMessage) {
        return failed(chapterId, taskId, errorCode, errorMessage, null);
    }

    public static ChapterReplyEvent failed(
            Long chapterId,
            Long taskId,
            String errorCode,
            String errorMessage,
            String diagnosticRef) {
        return new ChapterReplyEvent("reply.failed", chapterId, taskId, null, null, errorCode, errorMessage,
                PublicFailureFactory.from(errorCode, diagnosticRef));
    }

    /**
     * 创建回复取消事件。
     *
     * @param chapterId 章节 ID
     * @param taskId 任务 ID
     * @param messageId 已持久化的部分消息 ID，无增量时为空
     * @return 回复取消事件
     */
    public static ChapterReplyEvent canceled(Long chapterId, Long taskId, Long messageId) {
        return new ChapterReplyEvent("reply.canceled", chapterId, taskId, messageId, null, null, null);
    }

    /**
     * 保留无部分消息的取消事件构造入口。
     *
     * @param chapterId 章节 ID
     * @param taskId 任务 ID
     * @return 回复取消事件
     */
    public static ChapterReplyEvent canceled(Long chapterId, Long taskId) {
        return canceled(chapterId, taskId, null);
    }
}
