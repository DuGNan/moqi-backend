package com.dugnan.moqi.chapter.selection;

import java.time.LocalDateTime;
import java.util.List;

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
            String idempotencyKey) {
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
            LocalDateTime modifiedAt) {
    }
}
