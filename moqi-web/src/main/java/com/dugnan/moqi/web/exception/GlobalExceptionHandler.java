package com.dugnan.moqi.web.exception;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.api.PublicFailureFactory;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:统一处理 Web 层异常并转换为标准 API 响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常并转换为统一错误响应。
     *
     * @param exception 业务异常
     * @return 业务错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleBusinessException(BusinessException exception) {
        HttpStatus status = switch (exception.getErrorCode()) {
            case WORK_NOT_FOUND, CHAPTER_NOT_FOUND, CONVERSATION_NOT_FOUND,
                    OUTLINE_NOT_FOUND, GENERATION_NOT_FOUND, CHAPTER_BRIEF_NOT_FOUND,
                    OUTLINE_CANDIDATE_NOT_FOUND, SETTING_CANDIDATE_NOT_FOUND,
                    SETTING_NOT_FOUND, AI_TASK_NOT_FOUND, AGENT_RUN_NOT_FOUND,
                    AGENT_STEP_NOT_FOUND, AGENT_WORKFLOW_NOT_FOUND, NARRATIVE_PLAN_NOT_FOUND,
                    SCENE_PLAN_NOT_FOUND, STORY_CONTEXT_SNAPSHOT_NOT_FOUND,
                    GENERATION_SCENE_NOT_FOUND,
                    KNOWLEDGE_EXTRACTION_NOT_FOUND,
                    CHAPTER_CAPACITY_ASSESSMENT_NOT_FOUND,
                    PROSE_CANDIDATE_NOT_FOUND, PROSE_IMPACT_REPORT_NOT_FOUND,
                    PROSE_REVISION_NOT_FOUND, REVISION_WORKSPACE_NOT_FOUND,
                    STORY_RELEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case OUTLINE_REVISION_CONFLICT, GENERATION_STATUS_CONFLICT,
                    CHAPTER_VERSION_CONFLICT, WORK_VERSION_CONFLICT, CONFIG_VERSION_CONFLICT,
                    SETTING_CANDIDATE_CONFLICT, AI_TASK_STATE_CONFLICT,
                    CHAPTER_BRIEF_VERSION_CONFLICT, CHAPTER_BRIEF_CONFIRMATION_BLOCKED,
                    CHAPTER_CONFIRMED_BRIEF_REQUIRED, DISCUSSION_FOCUS_STALE,
                    OUTLINE_CANDIDATE_STATE_CONFLICT, OUTLINE_CANDIDATE_STALE,
                    OUTLINE_CANDIDATE_BRIEF_STALE, AGENT_RUN_IDEMPOTENCY_CONFLICT,
                    AGENT_RUN_STATE_CONFLICT, AGENT_STEP_RETRY_EXHAUSTED,
                    AGENT_RESUME_TOKEN_INVALID, AGENT_CHECKPOINT_INVALID,
                    AGENT_RUN_TIMED_OUT, NARRATIVE_PLAN_CONFLICT, SCENE_PLAN_CONFLICT,
                    SCENE_PLAN_OUTLINE_STALE, GENERATION_CONFIG_STALE,
                    KNOWLEDGE_EXTRACTION_CONFLICT,
                    KNOWLEDGE_EXTRACTION_STALE,
                    CHAPTER_CAPACITY_STATE_CONFLICT,
                    CHAPTER_CAPACITY_ASSESSMENT_STALE,
                    CHAPTER_CAPACITY_DECISION_REQUIRED,
                    CHAPTER_CAPACITY_LONG_CONTEXT_REQUIRED,
                    PROSE_REVISION_CONFLICT, REVISION_WORKSPACE_CONFLICT,
                    STORY_RELEASE_CONFLICT, PROSE_CANDIDATE_CONFLICT,
                    PROSE_WORKSPACE_CONFLICT, PROSE_IMPACT_REPORT_CONFLICT -> HttpStatus.CONFLICT;
            case MODEL_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        return failure(
                status,
                exception.getErrorCode(),
                PublicFailureFactory.safeMessage(exception.getErrorCode(), exception.getMessage()),
                PublicFailureFactory.safeData(exception.getData()),
                exception,
                status.is5xxServerError());
    }

    /**
     * 处理请求参数校验异常。
     *
     * @param exception 参数校验异常
     * @return 参数校验错误响应
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleValidationException(Exception exception) {
        return failure(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "请求参数校验失败", Map.of(), exception, false);
    }

    /**
     * 处理不可读取的请求体。
     *
     * @param exception 请求体解析异常
     * @return 请求体错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleUnreadableMessage(
            HttpMessageNotReadableException exception) {
        return failure(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "请求体不是有效 JSON", Map.of(), exception, false);
    }

    /**
     * 处理请求参数类型转换失败。
     *
     * @param exception 参数类型转换异常
     * @return 参数类型错误响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        return failure(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "请求参数类型错误", Map.of(), exception, false);
    }

    /**
     * 客户端断开 SSE 等异步响应后不再尝试写入统一 JSON 错误体。
     *
     * @param exception 已无法继续使用的异步请求
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException exception) {
        LOGGER.debug("客户端已断开异步请求: {}", exception.getMessage());
    }

    /**
     * 处理不存在的 API 路由，避免被通用异常处理器转换为 500。
     *
     * @param exception 未匹配到 Controller 的请求
     * @return 路由不存在响应
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleApiNotFound(Exception exception) {
        return failure(HttpStatus.NOT_FOUND, ErrorCode.API_NOT_FOUND, "接口不存在", Map.of(), exception, false);
    }

    /**
     * 处理未预期的系统异常。
     *
     * @param exception 未预期异常
     * @return 系统错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleUnexpectedException(Exception exception) {
        return failure(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR,
                "服务暂时无法完成请求",
                Map.of(),
                exception,
                true);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> failure(
            HttpStatus status,
            ErrorCode errorCode,
            String message,
            Map<String, Object> data,
            Exception exception,
            boolean logStackTrace) {
        String diagnosticRef = PublicFailureFactory.newDiagnosticRef();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("diagnosticRef", diagnosticRef)) {
            if (logStackTrace) {
                LOGGER.error("接口处理失败，exceptionType={}, diagnosticRef={}",
                        exception.getClass().getName(), diagnosticRef);
            } else {
                LOGGER.info("接口请求未完成，errorCode={}, diagnosticRef={}", errorCode, diagnosticRef);
            }
            ApiResponse<Map<String, Object>> response =
                    ApiResponse.failure(errorCode, message, data, diagnosticRef);
            return ResponseEntity.status(status)
                    .header("X-Diagnostic-Ref", diagnosticRef)
                    .body(response);
        }
    }
}
