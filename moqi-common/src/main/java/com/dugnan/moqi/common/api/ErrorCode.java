package com.dugnan.moqi.common.api;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:定义后端统一错误码。
 */
public enum ErrorCode {
    /** 请求处理成功。 */
    SUCCESS,
    /** 请求参数错误。 */
    BAD_REQUEST,
    /** 作品不存在。 */
    WORK_NOT_FOUND,
    /** 章节不存在。 */
    CHAPTER_NOT_FOUND,
    /** 通用业务错误。 */
    BUSINESS_ERROR,
    /** 服务内部错误。 */
    INTERNAL_ERROR
}
