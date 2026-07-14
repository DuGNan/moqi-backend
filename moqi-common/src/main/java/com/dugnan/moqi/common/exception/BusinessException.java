package com.dugnan.moqi.common.exception;

import com.dugnan.moqi.common.api.ErrorCode;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:表示携带统一错误码的业务异常。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

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
        super(message);
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
}
