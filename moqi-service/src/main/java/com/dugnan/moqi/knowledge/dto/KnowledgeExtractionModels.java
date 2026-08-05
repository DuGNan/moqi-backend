package com.dugnan.moqi.knowledge.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 定义已采纳正文知识提取、候选确认和来源追踪的公开契约。
 */
public final class KnowledgeExtractionModels {

    private KnowledgeExtractionModels() {
    }

    public record StartExtractionRequest(String idempotencyKey) {
    }

    public record RetryExtractionRequest(Integer expectedAttempt) {
    }

    public record ConfirmCandidateRequest(
            Integer baseVersion,
            String resolution,
            Long mergeTargetId,
            Map<String, Object> resolvedPayload) {
    }

    public record IgnoreCandidateRequest(Integer baseVersion) {
    }

    public record Evidence(Integer startOffset, Integer endOffset, String text) {
    }

    public record ExtractedCandidate(
            String candidateKey,
            String candidateType,
            Map<String, Object> payload,
            Evidence evidence) {
    }

    public record ExtractionOutput(Integer schemaVersion, List<ExtractedCandidate> candidates) {
    }

    public record CandidateView(
            Long id,
            Long batchId,
            String candidateKey,
            String candidateType,
            String candidateStatus,
            Map<String, Object> payload,
            Evidence evidence,
            Long conflictTargetId,
            String confirmedTargetType,
            Long confirmedTargetId,
            Integer version,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record BatchView(
            Long id,
            Long workId,
            Long chapterId,
            Long generationId,
            Long aiTaskId,
            Long agentRunId,
            String extractorVersion,
            Integer sourceContentRevision,
            String sourceFingerprint,
            String batchStatus,
            Integer candidateCount,
            String errorCode,
            List<CandidateView> candidates,
            Integer version,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record CandidateDecision(
            Long candidateId,
            String candidateStatus,
            String targetType,
            Long targetId,
            Integer version,
            LocalDateTime gmtModified) {
    }

    public record ExtractionAction(BatchView batch, AgentRunView run) {
    }
}
