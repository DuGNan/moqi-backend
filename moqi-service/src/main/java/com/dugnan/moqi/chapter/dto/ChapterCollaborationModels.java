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
            Long focusBriefId,
            String focusDecisionKey,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {

        /**
         * 保留不含讨论对焦引用的旧构造入口。
         *
         * @param id 消息 ID
         * @param conversationId 会话 ID
         * @param chapterId 章节 ID
         * @param messageRole 消息角色
         * @param content 消息正文
         * @param aiTaskId 关联 AI 任务 ID
         * @param gmtCreate 创建时间
         * @param gmtModified 修改时间
         */
        public MessageDetail(
                Long id,
                Long conversationId,
                Long chapterId,
                String messageRole,
                String content,
                Long aiTaskId,
                LocalDateTime gmtCreate,
                LocalDateTime gmtModified) {
            this(id, conversationId, chapterId, messageRole, content, aiTaskId, null, null, gmtCreate, gmtModified);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MessageCreated(
            Long id,
            Long conversationId,
            Long chapterId,
            String messageRole,
            String content,
            Long aiTaskId,
            Long focusBriefId,
            String focusDecisionKey,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {

        /**
         * 保留不含讨论对焦引用的旧构造入口。
         *
         * @param id 消息 ID
         * @param conversationId 会话 ID
         * @param chapterId 章节 ID
         * @param messageRole 消息角色
         * @param content 消息正文
         * @param aiTaskId 关联 AI 任务 ID
         * @param gmtCreate 创建时间
         * @param gmtModified 修改时间
         */
        public MessageCreated(
                Long id,
                Long conversationId,
                Long chapterId,
                String messageRole,
                String content,
                Long aiTaskId,
                LocalDateTime gmtCreate,
                LocalDateTime gmtModified) {
            this(id, conversationId, chapterId, messageRole, content, aiTaskId, null, null, gmtCreate, gmtModified);
        }
    }

    public record SendMessageRequest(
            String messageRole,
            String content,
            Boolean createAiTask,
            DiscussionFocusRequest discussionFocus,
            ReplyControlRequest replyControl) {

        /**
         * 保留不含讨论对焦的旧构造入口。
         *
         * @param messageRole 消息角色
         * @param content 消息正文
         * @param createAiTask 是否创建 AI 回复任务
         */
        public SendMessageRequest(String messageRole, String content, Boolean createAiTask) {
            this(messageRole, content, createAiTask, null, null);
        }

        /**
         * 保留仅包含讨论对焦的兼容构造入口。
         *
         * @param messageRole 消息角色
         * @param content 消息正文
         * @param createAiTask 是否创建 AI 回复任务
         * @param discussionFocus 讨论对焦
         */
        public SendMessageRequest(
                String messageRole,
                String content,
                Boolean createAiTask,
                DiscussionFocusRequest discussionFocus) {
            this(messageRole, content, createAiTask, discussionFocus, null);
        }
    }

    /**
     * 单次消息的回复深度与范围控制。
     *
     * @param depth auto、brief、balanced 或 deep
     * @param scopeMode auto 或 current_only
     * @param scopeText 可选的本轮范围说明
     */
    public record ReplyControlRequest(String depth, String scopeMode, String scopeText) {
    }

    /**
     * 讨论对焦只接受 Brief 与待决键引用。
     *
     * @param briefId Brief ID
     * @param decisionKey 待决键
     */
    public record DiscussionFocusRequest(Long briefId, String decisionKey) {
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

    public record OutlineRequest(
            String outlineContent,
            String outlineStatus,
            Integer baseRevision,
            Long confirmedBriefId) {

        /**
         * 保留不显式选择 confirmed Brief 的旧构造入口。
         *
         * @param outlineContent 大纲内容
         * @param outlineStatus 大纲状态
         * @param baseRevision 基础修订版本
         */
        public OutlineRequest(String outlineContent, String outlineStatus, Integer baseRevision) {
            this(outlineContent, outlineStatus, baseRevision, null);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OutlineDetail(
            Long id,
            Long workId,
            Long chapterId,
            Long confirmedBriefId,
            String outlineStatus,
            String outlineContent,
            Integer revision,
            ConsensusImpact consensusImpact,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {

        /**
         * 保留不含共识绑定和影响摘要的旧构造入口。
         *
         * @param id 大纲 ID
         * @param workId 作品 ID
         * @param chapterId 章节 ID
         * @param outlineStatus 大纲状态
         * @param outlineContent 大纲内容
         * @param revision 修订版本
         * @param gmtCreate 创建时间
         * @param gmtModified 修改时间
         */
        public OutlineDetail(
                Long id,
                Long workId,
                Long chapterId,
                String outlineStatus,
                String outlineContent,
                Integer revision,
                LocalDateTime gmtCreate,
                LocalDateTime gmtModified) {
            this(
                    id,
                    workId,
                    chapterId,
                    null,
                    outlineStatus,
                    outlineContent,
                    revision,
                    null,
                    gmtCreate,
                    gmtModified);
        }
    }

    /**
     * 大纲对三类核心共识的保守承接判断。
     */
    public record ConsensusImpact(
            DimensionImpact chapterTask,
            DimensionImpact stateChange,
            DimensionImpact readerProgress) {
    }

    /**
     * 单个共识维度的承接判断。
     *
     * @param status preserved 或 possibly_changed
     * @param reason 判断依据
     */
    public record DimensionImpact(String status, String reason) {
    }
}
