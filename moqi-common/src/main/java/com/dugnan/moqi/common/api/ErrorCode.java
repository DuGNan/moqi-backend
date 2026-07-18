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
    /** 会话不存在。 */
    CONVERSATION_NOT_FOUND,
    /** 设定候选不存在。 */
    SETTING_CANDIDATE_NOT_FOUND,
    /** 正式设定不存在。 */
    SETTING_NOT_FOUND,
    /** AI 任务不存在。 */
    AI_TASK_NOT_FOUND,
    /** 大纲修订版本冲突。 */
    OUTLINE_REVISION_CONFLICT,
    /** 章节大纲不存在。 */
    OUTLINE_NOT_FOUND,
    /** 章节生成记录不存在。 */
    GENERATION_NOT_FOUND,
    /** 章节生成记录状态不允许当前操作。 */
    GENERATION_STATUS_CONFLICT,
    /** 章节正文版本冲突。 */
    CHAPTER_VERSION_CONFLICT,
    /** 用户配置版本冲突。 */
    CONFIG_VERSION_CONFLICT,
    /** 通用业务错误。 */
    BUSINESS_ERROR,
    /** 服务内部错误。 */
    INTERNAL_ERROR
}
