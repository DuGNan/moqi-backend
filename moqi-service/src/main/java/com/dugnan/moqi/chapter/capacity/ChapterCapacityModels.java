package com.dugnan.moqi.chapter.capacity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 定义章节容量评估请求、结构化结果与只读响应契约。
 */
public final class ChapterCapacityModels {

    public static final String RESULT_FITS = "fits";
    public static final String RESULT_TOO_DENSE = "too_dense";
    public static final String RESULT_TOO_THIN = "too_thin";
    public static final String RESULT_REQUIRES_LONG_CONTEXT = "requires_long_context";
    public static final String DECISION_CONTINUE_LONG_CHAPTER = "continue_long_chapter";

    private ChapterCapacityModels() {
    }

    public record CreateAssessmentRequest(
            Integer scenePlanNo,
            String lengthPreset,
            Integer customWordCount,
            String idempotencyKey) {
    }

    public record RetryAssessmentRequest(Integer expectedAttempt) {
    }

    public record EventWeight(String eventKey, String label, String weight, String reason) {
    }

    public record CapacityResult(
            String status,
            Integer suggestedMinimumWordCount,
            Integer suggestedMaximumWordCount,
            List<String> reasons,
            List<EventWeight> eventWeights,
            List<String> compressibleItems,
            List<String> nonCompressibleCausalNodes,
            List<String> splitSuggestions,
            List<String> availableActions,
            String assessmentMode,
            String degradedReason,
            Boolean longContextRequired) {

        public CapacityResult {
            reasons = copy(reasons);
            eventWeights = eventWeights == null ? List.of() : List.copyOf(eventWeights);
            compressibleItems = copy(compressibleItems);
            nonCompressibleCausalNodes = copy(nonCompressibleCausalNodes);
            splitSuggestions = copy(splitSuggestions);
            availableActions = copy(availableActions);
            longContextRequired = Boolean.TRUE.equals(longContextRequired);
        }

        private static List<String> copy(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    public record CapacityAssessmentView(
            Long id,
            Long workId,
            Long chapterId,
            Long chapterPlanVersionId,
            Integer scenePlanNo,
            Integer targetWordCount,
            String status,
            CapacityResult result,
            String briefTemplateVersion,
            String briefFingerprint,
            String inputFingerprint,
            Long aiTaskId,
            Long agentRunId,
            Long modelCallId,
            String errorCode,
            String errorMessage,
            Integer currentAttempt,
            Boolean retryable,
            Integer version,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {

        /** 兼容恢复元数据发布前的内部构造调用。 */
        public CapacityAssessmentView(
                Long id,
                Long workId,
                Long chapterId,
                Long chapterPlanVersionId,
                Integer scenePlanNo,
                Integer targetWordCount,
                String status,
                CapacityResult result,
                String briefTemplateVersion,
                String briefFingerprint,
                String inputFingerprint,
                Long aiTaskId,
                Long agentRunId,
                Long modelCallId,
                String errorCode,
                String errorMessage,
                Integer version,
                LocalDateTime gmtCreate,
                LocalDateTime gmtModified) {
            this(id, workId, chapterId, chapterPlanVersionId, scenePlanNo, targetWordCount, status, result,
                    briefTemplateVersion, briefFingerprint, inputFingerprint, aiTaskId, agentRunId, modelCallId,
                    errorCode, errorMessage, null, false, version, gmtCreate, gmtModified);
        }
    }
}
