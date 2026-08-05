package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 定义正文一致性评价报告和局部修订候选的接口契约。
 */
public final class GenerationEvaluationModels {

    private GenerationEvaluationModels() {
    }

    public record CreateEvaluationRequest(Long generationSceneId, String idempotencyKey) {
    }

    public record RetryEvaluationRequest(Integer expectedAttempt) {
    }

    public record EvaluationFinding(
            String issueKey,
            String category,
            String severity,
            Double confidence,
            String source,
            Long generationSceneId,
            String evidenceRange,
            String storyFactRef,
            String summary,
            String suggestedAction) {
    }

    public record RevisionCandidateView(
            Long id,
            Long reportId,
            Long generationId,
            Long generationSceneId,
            String candidateStatus,
            String revisionContent,
            List<String> evidenceRanges,
            LocalDateTime gmtCreate) {
    }

    public record EvaluationReportView(
            Long id,
            Long generationId,
            Long generationSceneId,
            Long contextSnapshotId,
            Long aiTaskId,
            Long agentRunId,
            String reportStatus,
            String conclusion,
            List<EvaluationFinding> findings,
            String rulesetVersion,
            String evaluatorVersion,
            Integer revisionAttempt,
            RevisionCandidateView revisionCandidate,
            Integer version,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }
}
