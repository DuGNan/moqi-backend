package com.dugnan.moqi.common.exception;

import com.dugnan.moqi.common.api.ErrorCode;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:表示携带统一错误码的业务异常。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(String message) {
        this(ErrorCode.BUSINESS_ERROR, message);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
