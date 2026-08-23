package com.dugnan.moqi.chapter.selection;

import java.time.LocalDateTime;
import java.util.List;

import com.dugnan.moqi.common.api.PublicFailure;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 定义章节选区讨论、局部改写候选及人工处置的公开传输契约。
 */
public final class SelectionAssistanceModels {

    private SelectionAssistanceModels() {
    }

    public record CreateRequest(
            Integer baseVersion,
            String contentHash,
            Integer selectionStart,
            Integer selectionEnd,
            String selectedText,
            String operation,
            String instruction,
            Long parentId,
            String idempotencyKey,
            String targetKind,
            String targetId,
            Integer targetVersion,
            String referenceScope) {

        /** 兼容只面向章节正式正文的旧请求构造。 */
        public CreateRequest(
                Integer baseVersion,
                String contentHash,
                Integer selectionStart,
                Integer selectionEnd,
                String selectedText,
                String operation,
                String instruction,
                Long parentId,
                String idempotencyKey) {
            this(baseVersion, contentHash, selectionStart, selectionEnd, selectedText, operation, instruction,
                    parentId, idempotencyKey, null, null, null, null);
        }
    }

    public record ContinueRequest(String instruction, String idempotencyKey) {
    }

    public record RetryRequest(Integer expectedAttempt) {
    }

    public record AcceptRequest(Integer baseVersion, String contentHash) {
    }

    public record TextDiff(String original, String replacement, int originalLength, int replacementLength) {
    }

    public record View(
            Long id,
            Long workId,
            Long chapterId,
            Long parentId,
            Long aiTaskId,
            Long agentRunId,
            String operation,
            String status,
            Integer baseVersion,
            String contentHash,
            Integer selectionStart,
            Integer selectionEnd,
            String selectedText,
            String instruction,
            String briefTemplateVersion,
            String briefFingerprint,
            String inputFingerprint,
            String resultContent,
            TextDiff diff,
            String factRiskStatus,
            List<String> factRiskReasons,
            boolean canAccept,
            String planningChangeSuggestion,
            String errorCode,
            String errorMessage,
            Integer acceptedChapterVersion,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime modifiedAt,
            PublicFailure failure,
            String targetKind,
            String targetId,
            Integer targetVersion,
            String targetContentHash,
            String referenceScope,
            String referenceTextHash,
            Integer referenceSentenceCount,
            boolean referenceStale,
            Long createdCandidateId,
            String createdCandidateObjectId,
            String proposalStatus,
            Integer appliedCandidateVersion,
            String appliedCandidateHash,
            Long conversationId,
            Long userMessageId,
            Long assistantMessageId,
            Long planningChangePackageId) {

        /** 兼容公共失败字段发布前的构造调用。 */
        public View(
                Long id, Long workId, Long chapterId, Long parentId, Long aiTaskId, Long agentRunId,
                String operation, String status, Integer baseVersion, String contentHash,
                Integer selectionStart, Integer selectionEnd, String selectedText, String instruction,
                String briefTemplateVersion, String briefFingerprint, String inputFingerprint,
                String resultContent, TextDiff diff, String factRiskStatus, List<String> factRiskReasons,
                boolean canAccept, String planningChangeSuggestion, String errorCode, String errorMessage,
                Integer acceptedChapterVersion, Integer version, LocalDateTime createdAt,
                LocalDateTime modifiedAt) {
            this(id, workId, chapterId, parentId, aiTaskId, agentRunId, operation, status, baseVersion,
                    contentHash, selectionStart, selectionEnd, selectedText, instruction, briefTemplateVersion,
                    briefFingerprint, inputFingerprint, resultContent, diff, factRiskStatus, factRiskReasons,
                    canAccept, planningChangeSuggestion, errorCode, errorMessage, acceptedChapterVersion,
                    version, createdAt, modifiedAt, null, null, null, null, null, null, null, null,
                    false, null, null, null, null, null, null, null, null, null);
        }
    }

    public record PlanningContext(
            Long baseOutlineId,
            Integer baseOutlineRevision,
            Integer baseOutlineVersion,
            Long baseScenePlanId,
            Integer baseScenePlanVersion,
            String beforeSummary,
            List<ScenePlanContent> scenes) {
    }

    public record ModelPlanningProposal(
            String changeReason,
            String beforeSummary,
            String afterSummary,
            List<ScenePlanContent> scenes) {
    }

    public record PlanningChangePackageView(
            Long id,
            String targetObjectId,
            Integer targetContentVersion,
            String status,
            String changeSummary,
            String beforeSummary,
            String afterSummary,
            Integer baseOutlineRevision,
            Integer baseOutlineVersion,
            Integer baseScenePlanVersion,
            List<ScenePlanContent> scenes,
            Integer appliedCandidateVersion,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime modifiedAt) {
    }
}
