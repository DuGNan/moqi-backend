package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @author dgn
 * @date 2026-07-16
 * @description 集中定义章节共创、简报和大纲接口数据模型。
 */
public final class ChapterCollaborationModels {

    /**
     * 禁止实例化模型容器。
     */
    private ChapterCollaborationModels() {
    }

    public record ConversationDetail(
            Long id,
            Long workId,
            Long chapterId,
            String conversationType,
            String conversationStatus,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record MessageList(List<MessageDetail> messages) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MessageDetail(
            Long id,
            Long conversationId,
            Long chapterId,
            String messageRole,
            String content,
            Long aiTaskId,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MessageCreated(
            Long id,
            Long conversationId,
            Long chapterId,
            String messageRole,
            String content,
            Long aiTaskId,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record SendMessageRequest(String messageRole, String content, Boolean createAiTask) {
    }

    public record BriefRequest(String briefContent, String briefStatus) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BriefDetail(
            Long id,
            Long workId,
            Long chapterId,
            String briefStatus,
            String briefContent,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record OutlineRequest(String outlineContent, String outlineStatus, Integer baseRevision) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OutlineDetail(
            Long id,
            Long workId,
            Long chapterId,
            String outlineStatus,
            String outlineContent,
            Integer revision,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }
}
