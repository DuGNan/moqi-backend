package com.dugnan.moqi.common.api;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:统一封装 API 成功与失败响应。
 */
public record ApiResponse<T>(
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        T data,
        OffsetDateTime timestamp) {

    /**
     * 创建成功响应。
     *
     * @param data 业务数据
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.name(), "ok", data, OffsetDateTime.now());
    }

    /**
     * 创建失败响应。
     *
     * @param errorCode 统一错误码
     * @param message 错误消息
     * @param data 错误附加数据
     * @param <T> 数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message, T data) {
        return new ApiResponse<>(errorCode.name(), message, data, OffsetDateTime.now());
    }
}
