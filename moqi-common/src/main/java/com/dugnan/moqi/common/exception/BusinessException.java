package com.dugnan.moqi.common.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.dugnan.moqi.common.api.ErrorCode;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:表示携带统一错误码的业务异常。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    private final Map<String, Object> data;

    /**
     * 使用默认业务错误码创建异常。
     *
     * @param message 异常消息
     */
    public BusinessException(String message) {
        this(ErrorCode.BUSINESS_ERROR, message);
    }

    /**
     * 使用指定错误码创建异常。
     *
     * @param errorCode 统一错误码
     * @param message 异常消息
     */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    /**
     * 使用指定错误码和附加数据创建异常。
     *
     * @param errorCode 统一错误码
     * @param message 异常消息
     * @param data 错误附加数据
     */
    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> data) {
        this(errorCode, message, data, null);
    }

    /**
     * 使用指定错误码和原始 cause 创建异常。
     *
     * @param errorCode 统一错误码
     * @param message 异常消息
     * @param cause 原始异常
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, Map.of(), cause);
    }

    /**
     * 使用指定错误码、附加数据和原始 cause 创建异常。
     *
     * @param errorCode 统一错误码
     * @param message 异常消息
     * @param data 错误附加数据
     * @param cause 原始异常
     */
    public BusinessException(
            ErrorCode errorCode,
            String message,
            Map<String, Object> data,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.data = data == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /**
     * 使用指定错误码和原始异常创建业务异常。
     *
     * @param errorCode 统一错误码
     * @param message 异常消息
     * @param cause 原始异常
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取统一错误码。
     *
     * @return 统一错误码
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 获取错误附加数据。
     *
     * @return 错误附加数据
     */
    public Map<String, Object> getData() {
        return data;
    }
}
