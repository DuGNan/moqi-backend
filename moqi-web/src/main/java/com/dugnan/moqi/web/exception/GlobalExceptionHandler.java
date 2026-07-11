package com.dugnan.moqi.web.exception;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, Object>> handleBusinessException(BusinessException exception) {
        return ApiResponse.failure(exception.getErrorCode(), exception.getMessage(), Map.of());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, Object>> handleValidationException(Exception exception) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errors", extractErrors(exception));
        return ApiResponse.failure(ErrorCode.BAD_REQUEST, "request validation failed", data);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Map<String, Object>> handleUnexpectedException(Exception exception) {
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
