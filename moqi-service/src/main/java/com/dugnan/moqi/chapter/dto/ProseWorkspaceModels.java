package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

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
            LocalDateTime createdAt,
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
            LocalDateTime createdAt,
            LocalDateTime modifiedAt) {
    }

    public record QualitySummary(
            String status,
            String conclusion,
            String contentHash,
            LocalDateTime evaluatedAt) {
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
