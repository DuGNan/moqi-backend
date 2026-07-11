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

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleBusinessException(BusinessException exception) {
        HttpStatus status = switch (exception.getErrorCode()) {
            case WORK_NOT_FOUND, CHAPTER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        ApiResponse<Map<String, Object>> response =
                ApiResponse.failure(exception.getErrorCode(), exception.getMessage(), Map.of());
        return ResponseEntity.status(status).body(response);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, Object>> handleValidationException(Exception exception) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errors", extractErrors(exception));
        return ApiResponse.failure(ErrorCode.BAD_REQUEST, "request validation failed", data);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, Object>> handleUnreadableMessage(HttpMessageNotReadableException exception) {
        return ApiResponse.failure(ErrorCode.BAD_REQUEST, "请求体不是有效 JSON", Map.of());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Map<String, Object>> handleUnexpectedException(Exception exception) {
        LOGGER.error("未处理的接口异常", exception);
        return ApiResponse.failure(ErrorCode.INTERNAL_ERROR, "internal server error", Map.of());
    }

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
