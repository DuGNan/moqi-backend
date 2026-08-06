package com.dugnan.moqi.web.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.common.api.ErrorCode;
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
                    KNOWLEDGE_EXTRACTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
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
                    KNOWLEDGE_EXTRACTION_STALE -> HttpStatus.CONFLICT;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        ApiResponse<Map<String, Object>> response =
                ApiResponse.failure(exception.getErrorCode(), exception.getMessage(), exception.getData());
        return ResponseEntity.status(status).body(response);
    }

    /**
     * 处理请求参数校验异常。
     *
     * @param exception 参数校验异常
     * @return 参数校验错误响应
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, Object>> handleValidationException(Exception exception) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errors", extractErrors(exception));
        return ApiResponse.failure(ErrorCode.BAD_REQUEST, "request validation failed", data);
    }

    /**
     * 处理不可读取的请求体。
     *
     * @param exception 请求体解析异常
     * @return 请求体错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, Object>> handleUnreadableMessage(HttpMessageNotReadableException exception) {
        return ApiResponse.failure(ErrorCode.BAD_REQUEST, "请求体不是有效 JSON", Map.of());
    }

    /**
     * 处理请求参数类型转换失败。
     *
     * @param exception 参数类型转换异常
     * @return 参数类型错误响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, Object>> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        return ApiResponse.failure(
                ErrorCode.BAD_REQUEST,
                "请求参数类型错误",
                Map.of("parameter", exception.getName()));
    }

    /**
     * 处理未预期的系统异常。
     *
     * @param exception 未预期异常
     * @return 系统错误响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Map<String, Object>> handleUnexpectedException(Exception exception) {
        LOGGER.error("未处理的接口异常", exception);
        return ApiResponse.failure(ErrorCode.INTERNAL_ERROR, "internal server error", Map.of());
    }

    /**
     * 提取参数校验错误字段及消息。
     *
     * @param exception 参数校验异常
     * @return 字段错误映射
     */
    private Map<String, String> extractErrors(Exception exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (exception instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            for (FieldError fieldError : methodArgumentNotValidException.getBindingResult().getFieldErrors()) {
                errors.put(fieldError.getField(), fieldError.getDefaultMessage());
            }
        }
        if (exception instanceof BindException bindException) {
            for (FieldError fieldError : bindException.getBindingResult().getFieldErrors()) {
                errors.put(fieldError.getField(), fieldError.getDefaultMessage());
            }
        }
        return errors;
    }
}
