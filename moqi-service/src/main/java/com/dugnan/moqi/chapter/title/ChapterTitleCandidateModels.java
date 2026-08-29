package com.dugnan.moqi.chapter.title;

import java.time.LocalDateTime;
import java.util.List;

import com.dugnan.moqi.common.api.PublicFailure;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 定义章节 AI 取名、恢复与显式采用的公开契约。
 */
public final class ChapterTitleCandidateModels {

    private ChapterTitleCandidateModels() {
    }

    public record CreateBatchRequest(
            String sourceKind,
            String sourceObjectId,
            Integer sourceVersion,
            String contentHash,
            String idempotencyKey) {
    }

    public record RetryRequest(Integer expectedAttempt) {
    }

    public record AdoptRequest(
            String title,
            Integer baseVersion,
            String idempotencyKey,
            Boolean userConfirmed,
            Boolean allowStaleSource) {
    }

    public record LatestBatchView(BatchView batch) {
    }

    public record CandidateView(
            Long id,
            Integer order,
            String title,
            String adoptedTitle,
            Integer adoptedChapterVersion,
            LocalDateTime adoptedAt) {
    }

    public record BatchView(
            Long id,
            Long workId,
            Long chapterId,
            Long aiTaskId,
            Long agentRunId,
            String status,
            String sourceKind,
            String sourceObjectId,
            Integer sourceVersion,
            String sourceContentHash,
            boolean sourceStale,
            Integer currentAttempt,
            List<CandidateView> candidates,
            String errorCode,
            String errorMessage,
            PublicFailure failure,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime modifiedAt) {
    }

    public record AdoptedTitleView(
            Long batchId,
            Long candidateId,
            String title,
            Integer chapterVersion,
            boolean idempotentReplay,
            LocalDateTime adoptedAt) {
    }
}
