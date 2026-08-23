package com.dugnan.moqi.release;

import java.time.LocalDateTime;
import java.util.List;
import com.dugnan.moqi.impact.ProseImpactModels.WorkspaceImpactSummary;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 定义不可变正文 revision、修订工作区和 Story Release 的公共契约。
 */
public final class StoryReleaseModels {
    private StoryReleaseModels() {
    }

    public record CreateRevisionRequest(
            Long parentRevisionId,
            Long sourceGenerationId,
            Long sourceBoundedRevisionId,
            String content,
            String idempotencyKey) {
    }

    public record BindEvaluationRequest(Long evaluationReportId, Integer expectedVersion) {
    }

    public record AbandonRevisionRequest(Integer expectedVersion) {
    }

    public record RevisionView(
            Long id,
            Long workId,
            Long chapterId,
            Long parentRevisionId,
            Long sourceGenerationId,
            Long sourceBoundedRevisionId,
            Long sourceSnapshotId,
            Long evaluationReportId,
            Integer revisionNo,
            String revisionOrigin,
            String revisionStatus,
            String content,
            String contentHash,
            Integer version,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record RevisionDiff(
            Long baseRevisionId,
            Long targetRevisionId,
            String baseContentHash,
            String targetContentHash,
            String baseContent,
            String targetContent,
            boolean changed) {
    }

    public record CreateWorkspaceRequest(String idempotencyKey) {
    }

    public record CandidateAdoptionDraftRequest(
            Long parentRevisionId,
            Long sourceGenerationId,
            String content,
            Long evaluationReportId,
            Integer expectedFormalVersion,
            String idempotencyKey) {
    }

    public record CandidateAdoptionDraft(
            Long revisionId,
            Long workspaceId,
            Integer workspaceVersion) {
    }

    public record PutWorkspaceChapterRequest(Long proseRevisionId, Integer expectedVersion) {
    }

    public record PrepareWorkspaceRequest(Integer expectedVersion) {
    }

    public record PublishWorkspaceRequest(
            Integer expectedVersion,
            String idempotencyKey,
            Boolean userConfirmed) {
    }

    public record AbandonWorkspaceRequest(Integer expectedVersion) {
    }

    public record WorkspaceChapterView(
            Long id,
            Long chapterId,
            Long proseRevisionId,
            Long baselineProseRevisionId,
            Integer baselineChapterVersion,
            String entryStatus,
            Integer version) {
    }

    public record WorkspaceView(
            Long id,
            Long workId,
            Long baselineReleaseId,
            Long publishedReleaseId,
            Integer baselineWorkVersion,
            String workspaceStatus,
            List<String> blockingItems,
            WorkspaceImpactSummary impactSummary,
            List<WorkspaceChapterView> chapters,
            Integer version,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record RollbackReleaseRequest(
            Long expectedCurrentReleaseId,
            Integer expectedWorkVersion,
            String idempotencyKey,
            Boolean userConfirmed) {
    }

    public record ReleaseChapterView(
            Long chapterId,
            Integer chapterNo,
            Long proseRevisionId,
            String contentHash) {
    }

    public record ReleaseView(
            Long id,
            Long workId,
            Long parentReleaseId,
            Long rollbackOfReleaseId,
            Integer releaseNo,
            String releaseStatus,
            String releaseHash,
            List<ReleaseChapterView> chapters,
            Integer version,
            LocalDateTime confirmedAt,
            LocalDateTime gmtCreate) {
    }

    public record ReleaseDiffEntry(
            Long chapterId,
            Long baseRevisionId,
            Long targetRevisionId,
            String baseContentHash,
            String targetContentHash,
            boolean changed) {
    }

    public record ReleaseDiff(
            Long baseReleaseId,
            Long targetReleaseId,
            List<ReleaseDiffEntry> chapters) {
    }
}
