/**
 * @author dgn
 * @date:2026-07-13
 * @description:统一封装 API 成功与失败响应。
 */
package com.dugnan.moqi.common.api;

import java.time.OffsetDateTime;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        OffsetDateTime timestamp) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.name(), "ok", data, OffsetDateTime.now());
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message, T data) {
        return new ApiResponse<>(errorCode.name(), message, data, OffsetDateTime.now());
    }
}
