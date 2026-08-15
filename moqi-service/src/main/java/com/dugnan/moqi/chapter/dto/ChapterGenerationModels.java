package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 集中定义章节生成与正文接口数据模型。
 */
public final class ChapterGenerationModels {

    /**
     * 禁止实例化模型容器。
     */
    private ChapterGenerationModels() {
    }

    public record CreateGenerationRequest(
            Long outlineId,
            Integer baseRevision,
            String generationMode,
            String lengthPreset,
            Integer customWordCount,
            Long confirmedBriefId) {

        /**
         * 保留不显式选择 confirmed Brief 的旧构造入口。
         *
         * @param outlineId 大纲 ID
         * @param baseRevision 基础修订版本
         * @param generationMode 生成模式
         * @param lengthPreset 长度预设
         * @param customWordCount 自定义字数
         */
        public CreateGenerationRequest(
                Long outlineId,
                Integer baseRevision,
                String generationMode,
                String lengthPreset,
                Integer customWordCount) {
            this(outlineId, baseRevision, generationMode, lengthPreset, customWordCount, null);
        }
    }

    public record GenerationCreated(
            Long generationId,
            Long aiTaskId,
            Long workId,
            Long chapterId,
            String generationStatus,
            LocalDateTime gmtCreate) {
    }

    public record GenerationDetail(
            Long id,
            Long workId,
            Long chapterId,
            Long chapterPlanVersionId,
            Long baseGenerationId,
            Long outlineId,
            Integer outlineRevision,
            String generationStatus,
            String generationMode,
            String lengthPreset,
            Integer customWordCount,
            Map<String, Object> basisSnapshot,
            String generatedContent,
            Integer wordCount,
            Long aiTaskId,
            Long agentRunId,
            Long sourceSnapshotId,
            String validityStatus,
            String contentAssemblyMode,
            String cohesionStatus,
            Long cohesionModelCallId,
            String cohesionTemplateVersion,
            Long generationModelCallId,
            String generationTemplateVersion,
            String generationFinishReason,
            Integer currentAttempt,
            Boolean retryable,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {

        /** 兼容恢复元数据发布前的内部构造调用。 */
        public GenerationDetail(
                Long id,
                Long workId,
                Long chapterId,
                Long chapterPlanVersionId,
                Long baseGenerationId,
                Long outlineId,
                Integer outlineRevision,
                String generationStatus,
                String generationMode,
                String lengthPreset,
                Integer customWordCount,
                Map<String, Object> basisSnapshot,
                String generatedContent,
                Integer wordCount,
                Long aiTaskId,
                Long agentRunId,
                Long sourceSnapshotId,
                String validityStatus,
                String contentAssemblyMode,
                String cohesionStatus,
                Long cohesionModelCallId,
                String cohesionTemplateVersion,
                Long generationModelCallId,
                String generationTemplateVersion,
                String generationFinishReason,
                LocalDateTime gmtCreate,
                LocalDateTime gmtModified) {
            this(id, workId, chapterId, chapterPlanVersionId, baseGenerationId, outlineId, outlineRevision,
                    generationStatus, generationMode, lengthPreset, customWordCount, basisSnapshot, generatedContent,
                    wordCount, aiTaskId, agentRunId, sourceSnapshotId, validityStatus, contentAssemblyMode,
                    cohesionStatus, cohesionModelCallId, cohesionTemplateVersion, generationModelCallId,
                    generationTemplateVersion, generationFinishReason, null, false, gmtCreate, gmtModified);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LatestPreview(
            Long generationId,
            Long chapterId,
            String generationStatus,
            String generationMode,
            Integer wordCount,
            LocalDateTime gmtCreate) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LatestActiveGeneration(
            Long generationId,
            Long chapterId,
            String generationStatus,
            String contentAssemblyMode,
            Long aiTaskId,
            Long agentRunId,
            String currentStepKey,
            Integer currentAttempt,
            Boolean retryable,
            LocalDateTime gmtModified) {
    }

    public record AcceptGenerationRequest(String applyMode, Integer baseVersion) {
    }

    public record GenerationAccepted(
            Long workId,
            Long chapterId,
            Long generationId,
            String generationStatus,
            Integer version,
            String workflowStatus,
            LocalDateTime gmtModified) {
    }

    public record RejectGenerationRequest(String reason) {
    }

    public record GenerationRejected(
            Long generationId,
            String generationStatus,
            LocalDateTime gmtModified) {
    }

    public record RegenerateRequest(
            String feedback,
            String generationMode,
            String lengthPreset,
            Integer customWordCount) {
    }

    public record ChapterContent(
            Long workId,
            Long chapterId,
            String title,
            String content,
            Integer version,
            Integer wordCount,
            LocalDateTime gmtModified) {
    }

    public record SaveContentRequest(String content, Integer baseVersion, String saveSource) {
    }

    public record ContentSaved(
            Long chapterId,
            boolean saved,
            Integer version,
            boolean conflict,
            Integer wordCount,
            LocalDateTime serverSavedAt) {
    }
}
