package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 集中定义章节结构化共识和 Brief 版本接口模型。
 */
public final class ChapterConsensusModels {

    /**
     * 禁止实例化模型容器。
     */
    private ChapterConsensusModels() {
    }

    /**
     * 创建结构化 Brief 草稿请求。
     *
     * @param consensus 结构化共识
     */
    public record CreateBriefDraftRequest(
            Long conversationId,
            Long baseBriefId,
            ChapterConsensusContentV1 consensus) {

        /**
         * 创建不声明基础版本的草稿请求。
         *
         * @param consensus 结构化共识
         */
        public CreateBriefDraftRequest(ChapterConsensusContentV1 consensus) {
            this(null, null, consensus);
        }

        /**
         * 创建不声明来源会话的兼容草稿请求。
         *
         * @param baseBriefId 基础 Brief ID
         * @param consensus 结构化共识
         */
        public CreateBriefDraftRequest(
                Long baseBriefId,
                ChapterConsensusContentV1 consensus) {
            this(null, baseBriefId, consensus);
        }
    }

    /**
     * 确认 Brief 请求。
     *
     * @param baseVersion 客户端读取到的 Brief 版本
     */
    public record ConfirmBriefRequest(Integer baseVersion) {
    }

    /**
     * @author dgn
     * @date 2026-08-03
     * @description 定义用户处理待决候选的乐观并发请求。
     */
    public record ResolveDecisionRequest(Integer baseVersion, String action) {
    }

    /**
     * 创建异步共识收束任务请求。
     *
     * @param conversationId 会话 ID
     * @param baseBriefId 基础 Brief ID，可空
     */
    public record ConsensusTaskRequest(Long conversationId, Long baseBriefId) {
    }

    /**
     * 已创建共识收束任务。
     *
     * @param taskId AI 任务 ID
     * @param taskStatus 任务状态
     * @param chapterId 章节 ID
     */
    public record ConsensusTaskCreated(Long taskId, String taskStatus, Long chapterId) {
    }

    /**
     * 章节 Brief 兼容视图。
     *
     * @param id Brief ID
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param briefStatus Brief 状态
     * @param version 乐观锁版本
     * @param contentFormat 内容格式
     * @param consensus 结构化共识
     * @param legacyText 历史自由文本
     * @param gmtCreate 创建时间
     * @param gmtModified 修改时间
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BriefView(
            Long id,
            Long workId,
            Long chapterId,
            String briefStatus,
            Integer version,
            String contentFormat,
            ChapterConsensusContentV1 consensus,
            String legacyText,
            String triggerSource,
            Long baseBriefId,
            String sourceAssetType,
            Long sourceAssetId,
            Long sourceReportId,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
        /** 兼容尚未携带来源链字段的调用方。 */
        public BriefView(Long id, Long workId, Long chapterId, String briefStatus, Integer version,
                String contentFormat, ChapterConsensusContentV1 consensus, String legacyText,
                LocalDateTime gmtCreate, LocalDateTime gmtModified) {
            this(id, workId, chapterId, briefStatus, version, contentFormat, consensus, legacyText, "manual", null,
                    null, null, null, gmtCreate, gmtModified);
        }
    }

    /**
     * 章节最新草稿和最新确认版本。
     *
     * @param latestDraft 最新草稿
     * @param latestConfirmed 最新确认版本
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BriefState(BriefView latestDraft, BriefView latestConfirmed) {
    }

    /**
     * 待决来源消息列表。
     *
     * @param briefId Brief ID
     * @param decisionKey 待决键
     * @param messages 来源消息
     */
    public record DecisionSources(
            Long briefId,
            String decisionKey,
            List<SourceMessagePreview> messages) {
    }

    /**
     * 来源消息预览。
     *
     * @param id 消息 ID
     * @param conversationId 会话 ID
     * @param messageRole 消息角色
     * @param contentPreview 内容预览
     * @param gmtCreate 创建时间
     */
    public record SourceMessagePreview(
            Long id,
            Long conversationId,
            String messageRole,
            String contentPreview,
            LocalDateTime gmtCreate,
            List<String> quotes) {

        public SourceMessagePreview(
                Long id,
                Long conversationId,
                String messageRole,
                String contentPreview,
                LocalDateTime gmtCreate) {
            this(id, conversationId, messageRole, contentPreview, gmtCreate, List.of());
        }
    }
}
