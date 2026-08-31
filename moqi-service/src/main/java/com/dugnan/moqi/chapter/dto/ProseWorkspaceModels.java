package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 定义统一章节正文工作区、候选目录和质量摘要的作者可见契约。
 */
public final class ProseWorkspaceModels {

    private ProseWorkspaceModels() {
    }

    public record ProseWorkspaceView(
            Long chapterId,
            FormalProseView formal,
            List<ProseCandidateSummary> candidates,
            WorkspaceSelectionView selection,
            List<RunningTaskSummary> runningTasks) {
    }

    public record FormalProseView(
            String objectId,
            String content,
            String contentHash,
            Integer version,
            Integer wordCount,
            boolean editable,
            Long publishedRevisionId,
            LocalDateTime modifiedAt) {
    }

    public record ProseCandidateSummary(
            Long candidateId,
            String objectId,
            Long rootCandidateId,
            Long parentCandidateId,
            String sourceKind,
            String candidateStatus,
            String adoptionStatus,
            Integer contentVersion,
            String contentHash,
            Integer wordCount,
            QualitySummary quality,
            AdoptionReadiness adoptionReadiness,
            LocalDateTime createdAt,
            LocalDateTime modifiedAt,
            Integer displayNo) {

        public ProseCandidateSummary(
                Long candidateId,
                String objectId,
                Long rootCandidateId,
                Long parentCandidateId,
                String sourceKind,
                String candidateStatus,
                String adoptionStatus,
                Integer contentVersion,
                String contentHash,
                Integer wordCount,
                QualitySummary quality,
                AdoptionReadiness adoptionReadiness,
                LocalDateTime createdAt,
                LocalDateTime modifiedAt) {
            this(candidateId, objectId, rootCandidateId, parentCandidateId, sourceKind, candidateStatus,
                    adoptionStatus, contentVersion, contentHash, wordCount, quality, adoptionReadiness,
                    createdAt, modifiedAt, null);
        }
    }

    public record AdoptionReadiness(
            boolean canAdopt,
            String adoptionMode,
            Long qualityReportId,
            List<String> blockingCodes,
            List<String> nextActions) {
    }

    public record ProseCandidateBasisView(
            String basisStatus,
            boolean editedAfterCreation,
            Long sourceGenerationId,
            String sourceContentHash,
            String currentContentHash,
            JsonNode outline,
            JsonNode scenes,
            JsonNode characters,
            JsonNode previousProse,
            JsonNode worldSettings,
            JsonNode creativeConstraints) {
    }

    public record ProseComparisonView(ComparisonSide left, ComparisonSide right) {
    }

    public record ComparisonSide(
            String objectKind,
            String objectId,
            String content,
            Integer version,
            String contentHash,
            Integer wordCount,
            Long rootCandidateId,
            Long parentCandidateId,
            String sourceKind,
            Long sourceGenerationId,
            Long sourceBoundedRevisionId,
            LocalDateTime modifiedAt) {
    }

    public record AdoptProseCandidateRequest(
            Integer candidateVersion,
            String contentHash,
            Integer expectedFormalVersion,
            Long qualityReportId,
            String idempotencyKey,
            Boolean userConfirmed) {
    }

    public record ProseCandidateAdoptionView(
            Long adoptionId,
            Long chapterId,
            Long candidateId,
            Integer candidateVersion,
            String contentHash,
            String adoptionMode,
            String status,
            Integer formalVersion,
            Long revisionId,
            Long workspaceId,
            Long impactReportId,
            LocalDateTime modifiedAt) {
    }

    public record ProseCandidateDetail(
            Long chapterId,
            Long candidateId,
            String objectId,
            Long rootCandidateId,
            Long parentCandidateId,
            String sourceKind,
            String candidateStatus,
            String adoptionStatus,
            String content,
            Integer contentVersion,
            String contentHash,
            Integer wordCount,
            QualitySummary quality,
            AdoptionReadiness adoptionReadiness,
            LocalDateTime createdAt,
            LocalDateTime modifiedAt,
            Integer displayNo) {

        public ProseCandidateDetail(
                Long chapterId,
                Long candidateId,
                String objectId,
                Long rootCandidateId,
                Long parentCandidateId,
                String sourceKind,
                String candidateStatus,
                String adoptionStatus,
                String content,
                Integer contentVersion,
                String contentHash,
                Integer wordCount,
                QualitySummary quality,
                LocalDateTime createdAt,
                LocalDateTime modifiedAt) {
            this(chapterId, candidateId, objectId, rootCandidateId, parentCandidateId, sourceKind,
                    candidateStatus, adoptionStatus, content, contentVersion, contentHash, wordCount,
                    quality, null, createdAt, modifiedAt, null);
        }

        public ProseCandidateDetail(
                Long chapterId,
                Long candidateId,
                String objectId,
                Long rootCandidateId,
                Long parentCandidateId,
                String sourceKind,
                String candidateStatus,
                String adoptionStatus,
                String content,
                Integer contentVersion,
                String contentHash,
                Integer wordCount,
                QualitySummary quality,
                AdoptionReadiness adoptionReadiness,
                LocalDateTime createdAt,
                LocalDateTime modifiedAt) {
            this(chapterId, candidateId, objectId, rootCandidateId, parentCandidateId, sourceKind,
                    candidateStatus, adoptionStatus, content, contentVersion, contentHash, wordCount,
                    quality, adoptionReadiness, createdAt, modifiedAt, null);
        }
    }

    public record QualitySummary(
            String status,
            String conclusion,
            String contentHash,
            LocalDateTime evaluatedAt,
            Long generationId,
            Long reportId,
            Integer currentAttempt,
            boolean retryable,
            String failureDescription) {

        /** 兼容安全重试摘要发布前的构造调用。 */
        public QualitySummary(
                String status,
                String conclusion,
                String contentHash,
                LocalDateTime evaluatedAt) {
            this(status, conclusion, contentHash, evaluatedAt, null, null, null, false, null);
        }
    }

    public record WorkspaceSelectionView(
            String objectKind,
            String objectId,
            Integer version,
            LocalDateTime modifiedAt) {
    }

    public record SaveWorkspaceSelectionRequest(
            String objectKind,
            String objectId,
            Integer baseVersion) {
    }

    public record SaveProseCandidateRequest(
            String content,
            Integer baseVersion,
            Long planningChangePackageId,
            Boolean planningConfirmed,
            List<Long> appliedProposalIds) {

        /** 兼容尚未提交修改提案结算字段的调用。 */
        public SaveProseCandidateRequest(
                String content,
                Integer baseVersion,
                Long planningChangePackageId,
                Boolean planningConfirmed) {
            this(content, baseVersion, planningChangePackageId, planningConfirmed, List.of());
        }
    }

    public record RunningTaskSummary(
            Long generationId,
            String status,
            String taskKind,
            LocalDateTime modifiedAt) {
    }
}
