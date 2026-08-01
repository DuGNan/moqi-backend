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
    /** 大纲调整候选不存在。 */
    OUTLINE_CANDIDATE_NOT_FOUND,
    /** 大纲调整候选状态不允许当前操作。 */
    OUTLINE_CANDIDATE_STATE_CONFLICT,
    /** 大纲调整候选内容不符合契约。 */
    OUTLINE_CANDIDATE_INVALID,
    /** 大纲调整候选基础大纲已过期。 */
    OUTLINE_CANDIDATE_STALE,
    /** 大纲调整候选绑定的 Brief 已被替换。 */
    OUTLINE_CANDIDATE_BRIEF_STALE,
    /** 章节生成记录不存在。 */
    GENERATION_NOT_FOUND,
    /** 章节生成记录状态不允许当前操作。 */
    GENERATION_STATUS_CONFLICT,
    /** 章节 Brief 不存在。 */
    CHAPTER_BRIEF_NOT_FOUND,
    /** 章节 Brief 版本冲突。 */
    CHAPTER_BRIEF_VERSION_CONFLICT,
    /** 章节 Brief 尚不满足确认条件。 */
    CHAPTER_BRIEF_CONFIRMATION_BLOCKED,
    /** 当前操作要求存在已确认的章节 Brief。 */
    CHAPTER_CONFIRMED_BRIEF_REQUIRED,
    /** 章节结构化共识不符合契约。 */
    CHAPTER_CONSENSUS_INVALID,
    /** 讨论对焦引用不符合契约或归属关系。 */
    DISCUSSION_FOCUS_INVALID,
    /** 讨论对焦引用的 Brief 已失效。 */
    DISCUSSION_FOCUS_STALE,
    /** 章节正文版本冲突。 */
    CHAPTER_VERSION_CONFLICT,
    /** 用户配置版本冲突。 */
    CONFIG_VERSION_CONFLICT,
    MODEL_UNAVAILABLE,
    /** 设定候选状态冲突。 */
    SETTING_CANDIDATE_CONFLICT,
    /** AI 任务状态冲突。 */
    AI_TASK_STATE_CONFLICT,
    /** Agent Run 不存在。 */
    AGENT_RUN_NOT_FOUND,
    /** Agent Step 不存在。 */
    AGENT_STEP_NOT_FOUND,
    /** Agent 工作流不存在。 */
    AGENT_WORKFLOW_NOT_FOUND,
    /** Agent Run 幂等键与输入不一致。 */
    AGENT_RUN_IDEMPOTENCY_CONFLICT,
    /** Agent Run 或 Step 状态已变化。 */
    AGENT_RUN_STATE_CONFLICT,
    /** Agent Step 已达到重试上限。 */
    AGENT_STEP_RETRY_EXHAUSTED,
    /** 人工恢复令牌无效、已过期或已被消费。 */
    AGENT_RESUME_TOKEN_INVALID,
    /** Agent checkpoint 无法用于恢复。 */
    AGENT_CHECKPOINT_INVALID,
    /** Agent Run 已超时。 */
    AGENT_RUN_TIMED_OUT,
    /** 作品叙事规划不存在。 */
    NARRATIVE_PLAN_NOT_FOUND,
    /** 尚未发布作品叙事规划。 */
    NARRATIVE_PLAN_REQUIRED,
    /** 作品叙事规划版本或状态冲突。 */
    NARRATIVE_PLAN_CONFLICT,
    /** 章节场景规划不存在。 */
    SCENE_PLAN_NOT_FOUND,
    /** 章节场景规划版本或状态冲突。 */
    SCENE_PLAN_CONFLICT,
    /** 场景规划绑定的大纲已过期。 */
    SCENE_PLAN_OUTLINE_STALE,
    /** 场景规划内容不符合结构化契约。 */
    SCENE_PLAN_INVALID,
    /** 故事上下文快照不存在。 */
    STORY_CONTEXT_SNAPSHOT_NOT_FOUND,
    /** 章节生成场景候选不存在。 */
    GENERATION_SCENE_NOT_FOUND,
    /** 章节生成选择参数不合法。 */
    GENERATION_SELECTION_INVALID,
    /** 章节生成绑定的模型配置或凭据已变化。 */
    GENERATION_CONFIG_STALE,
    /** 通用业务错误。 */
    BUSINESS_ERROR,
    /** 服务内部错误。 */
    INTERNAL_ERROR
}
