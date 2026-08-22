package com.dugnan.moqi.common.api;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 集中生成并分类公共失败契约，避免向作者界面泄漏内部错误详情。
 */
public final class PublicFailureFactory {

    private static final String CODE_AGENT_PREFIX = "AGENT_";
    private static final String CODE_FAILED_MARKER = "FAILED";
    private static final String CODE_INVALID_RESPONSE = "INVALID_RESPONSE";
    private static final String CODE_JSON_INVALID_SUFFIX = "_JSON_INVALID";
    private static final String CODE_RATE_LIMIT_MARKER = "RATE_LIMIT";
    private static final String CODE_TASK_PREFIX = "TASK_";
    private static final String CODE_TIMEOUT_MARKER = "TIMEOUT";
    private static final String CODE_UNAVAILABLE_MARKER = "UNAVAILABLE";
    private static final Set<String> RETRYABLE_CODES = Set.of(
            "MODEL_UNAVAILABLE", "PROVIDER_UNAVAILABLE", "TASK_QUEUE_FULL", "TIMEOUT",
            "AGENT_RUN_TIMED_OUT", "AGENT_EXECUTOR_REJECTED", "RATE_LIMITED");
    private static final Set<String> SAFE_DATA_KEYS = Set.of(
            "version", "expectedVersion", "currentVersion", "baseVersion", "latestVersion");

    private PublicFailureFactory() {
    }

    public static PublicFailure from(ErrorCode errorCode, String diagnosticRef) {
        ErrorCode safeCode = errorCode == null ? ErrorCode.INTERNAL_ERROR : errorCode;
        return new PublicFailure(
                safeCode.name(),
                category(safeCode),
                isRetryable(safeCode.name()),
                diagnosticRef);
    }

    public static PublicFailure from(String errorCode, String diagnosticRef) {
        String safeCode = normalize(errorCode);
        return new PublicFailure(safeCode, category(safeCode), isRetryable(safeCode), diagnosticRef);
    }

    public static String newDiagnosticRef() {
        return "diag_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String safeMessage(ErrorCode errorCode, String message) {
        ErrorCode safeCode = errorCode == null ? ErrorCode.INTERNAL_ERROR : errorCode;
        if (safeCode == ErrorCode.MODEL_UNAVAILABLE) {
            return "模型服务暂时不可用";
        }
        return genericMessage(category(safeCode));
    }

    public static String safeMessage(String errorCode, String message) {
        String safeCode = normalize(errorCode);
        try {
            return safeMessage(ErrorCode.valueOf(safeCode), null);
        } catch (IllegalArgumentException exception) {
            return genericMessage(category(safeCode));
        }
    }

    public static Map<String, Object> safeData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safeData = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (SAFE_DATA_KEYS.contains(entry.getKey()) && isSafeScalar(entry.getValue())) {
                safeData.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(safeData);
    }

    private static String category(ErrorCode errorCode) {
        return switch (errorCode) {
            case BAD_REQUEST, DISCUSSION_FOCUS_INVALID, MESSAGE_REFERENCE_INVALID,
                    OUTLINE_CANDIDATE_INVALID, GENERATION_SELECTION_INVALID,
                    SCENE_PLAN_INVALID, PROSE_IMPACT_REPORT_INVALID,
                    KNOWLEDGE_EXTRACTION_INVALID -> "validation";
            case API_NOT_FOUND, WORK_NOT_FOUND, CHAPTER_NOT_FOUND, CONVERSATION_NOT_FOUND,
                    SETTING_CANDIDATE_NOT_FOUND, SETTING_NOT_FOUND, AI_TASK_NOT_FOUND,
                    OUTLINE_NOT_FOUND, OUTLINE_CANDIDATE_NOT_FOUND, GENERATION_NOT_FOUND,
                    CHAPTER_BRIEF_NOT_FOUND, PROSE_CANDIDATE_NOT_FOUND, AGENT_RUN_NOT_FOUND,
                    AGENT_STEP_NOT_FOUND, AGENT_WORKFLOW_NOT_FOUND, NARRATIVE_PLAN_NOT_FOUND,
                    SCENE_PLAN_NOT_FOUND, STORY_CONTEXT_SNAPSHOT_NOT_FOUND,
                    GENERATION_SCENE_NOT_FOUND, CHAPTER_CAPACITY_ASSESSMENT_NOT_FOUND,
                    PROSE_REVISION_NOT_FOUND, REVISION_WORKSPACE_NOT_FOUND,
                    STORY_RELEASE_NOT_FOUND, PROSE_IMPACT_REPORT_NOT_FOUND,
                    KNOWLEDGE_EXTRACTION_NOT_FOUND -> "not_found";
            case OUTLINE_REVISION_CONFLICT, GENERATION_STATUS_CONFLICT, CHAPTER_VERSION_CONFLICT,
                    PROSE_CANDIDATE_CONFLICT, PROSE_WORKSPACE_CONFLICT, WORK_VERSION_CONFLICT,
                    CONFIG_VERSION_CONFLICT, SETTING_CANDIDATE_CONFLICT, AI_TASK_STATE_CONFLICT,
                    CHAPTER_BRIEF_VERSION_CONFLICT, DISCUSSION_FOCUS_STALE,
                    OUTLINE_CANDIDATE_STATE_CONFLICT, OUTLINE_CANDIDATE_STALE,
                    OUTLINE_CANDIDATE_BRIEF_STALE, AGENT_RUN_IDEMPOTENCY_CONFLICT,
                    AGENT_RUN_STATE_CONFLICT, AGENT_RESUME_TOKEN_INVALID,
                    AGENT_CHECKPOINT_INVALID, NARRATIVE_PLAN_CONFLICT, SCENE_PLAN_CONFLICT,
                    SCENE_PLAN_OUTLINE_STALE, SCENE_PLAN_SOURCE_STALE,
                    SCENE_PLAN_CONSISTENCY_CONFLICT, GENERATION_CONFIG_STALE,
                    CHAPTER_CAPACITY_STATE_CONFLICT, CHAPTER_CAPACITY_ASSESSMENT_STALE,
                    PROSE_REVISION_CONFLICT, REVISION_WORKSPACE_CONFLICT, STORY_RELEASE_CONFLICT,
                    PROSE_IMPACT_REPORT_CONFLICT, KNOWLEDGE_EXTRACTION_CONFLICT,
                    KNOWLEDGE_EXTRACTION_STALE -> "conflict";
            case MODEL_UNAVAILABLE, CHAPTER_CAPACITY_LONG_CONTEXT_REQUIRED -> "service_unavailable";
            case AGENT_STEP_RETRY_EXHAUSTED, AGENT_RUN_TIMED_OUT -> "task_failure";
            case INTERNAL_ERROR -> "internal";
            default -> "business_rejection";
        };
    }

    private static String category(String errorCode) {
        try {
            return category(ErrorCode.valueOf(errorCode));
        } catch (IllegalArgumentException exception) {
            if (errorCode.contains(CODE_UNAVAILABLE_MARKER) || errorCode.contains(CODE_RATE_LIMIT_MARKER)
                    || errorCode.contains("QUEUE_FULL")) {
                return "service_unavailable";
            }
            if (errorCode.contains(CODE_TIMEOUT_MARKER) || errorCode.contains(CODE_FAILED_MARKER)
                    || errorCode.contains("PROVIDER") || errorCode.contains("MODEL")
                    || CODE_INVALID_RESPONSE.equals(errorCode) || errorCode.endsWith(CODE_JSON_INVALID_SUFFIX)) {
                return "task_failure";
            }
            if (errorCode.startsWith(CODE_AGENT_PREFIX) || errorCode.startsWith(CODE_TASK_PREFIX)) {
                return "task_failure";
            }
            return "internal";
        }
    }

    private static boolean isRetryable(String errorCode) {
        return RETRYABLE_CODES.contains(errorCode)
                || errorCode.contains(CODE_UNAVAILABLE_MARKER)
                || errorCode.contains(CODE_TIMEOUT_MARKER)
                || errorCode.contains(CODE_RATE_LIMIT_MARKER)
                || errorCode.contains("QUEUE_FULL");
    }

    private static String normalize(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return ErrorCode.INTERNAL_ERROR.name();
        }
        String normalized = errorCode.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,64}") ? normalized : ErrorCode.INTERNAL_ERROR.name();
    }

    private static boolean isSafeScalar(Object value) {
        return value instanceof Number || value instanceof Boolean;
    }

    private static String genericMessage(String category) {
        return switch (category) {
            case "validation" -> "请求内容不符合要求";
            case "not_found" -> "请求的内容不存在";
            case "conflict" -> "数据已发生变化，请刷新后重试";
            case "service_unavailable" -> "依赖服务暂时不可用";
            case "task_failure" -> "任务未能完成，请稍后重试";
            case "business_rejection" -> "当前操作不满足执行条件";
            default -> "服务暂时无法完成请求";
        };
    }
}
