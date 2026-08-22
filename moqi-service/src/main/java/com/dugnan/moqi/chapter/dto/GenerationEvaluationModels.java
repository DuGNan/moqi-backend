package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.dugnan.moqi.common.api.PublicFailure;

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
            String suggestedAction,
            String violatedSource,
            String impactScope,
            Boolean blocksAcceptance,
            Boolean suitableForAutoRevision) {

        /** 兼容既有场景级评价 Finding。 */
        public EvaluationFinding(
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
            this(issueKey, category, severity, confidence, source, generationSceneId, evidenceRange,
                    storyFactRef, summary, suggestedAction, storyFactRef, null,
                    "blocking".equals(severity), false);
        }
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
            String contentHash,
            String briefFingerprint,
            String sourceFingerprint,
            Long modelCallId,
            String errorCode,
            String errorMessage,
            Integer currentAttempt,
            boolean retryable,
            Integer revisionAttempt,
            RevisionCandidateView revisionCandidate,
            Integer version,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified,
            PublicFailure failure) {

        /** 兼容公共失败字段发布前的构造调用。 */
        public EvaluationReportView(
                Long id, Long generationId, Long generationSceneId, Long contextSnapshotId, Long aiTaskId,
                Long agentRunId, String reportStatus, String conclusion, List<EvaluationFinding> findings,
                String rulesetVersion, String evaluatorVersion, String contentHash, String briefFingerprint,
                String sourceFingerprint, Long modelCallId, String errorCode, String errorMessage,
                Integer currentAttempt, boolean retryable, Integer revisionAttempt,
                RevisionCandidateView revisionCandidate, Integer version, LocalDateTime gmtCreate,
                LocalDateTime gmtModified) {
            this(id, generationId, generationSceneId, contextSnapshotId, aiTaskId, agentRunId, reportStatus,
                    conclusion, findings, rulesetVersion, evaluatorVersion, contentHash, briefFingerprint,
                    sourceFingerprint, modelCallId, errorCode, errorMessage, currentAttempt, retryable,
                    revisionAttempt, revisionCandidate, version, gmtCreate, gmtModified, null);
        }
    }
}
