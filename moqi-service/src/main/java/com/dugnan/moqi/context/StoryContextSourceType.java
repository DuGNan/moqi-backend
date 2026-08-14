package com.dugnan.moqi.context;

/**
 * 上下文候选来源类型。
 *
 * @author dgn
 */
public enum StoryContextSourceType {
    /** 系统规则。 */
    SYSTEM_RULE,
    /** 当前任务规则。 */
    TASK_RULE,
    /** 作品元数据。 */
    WORK_METADATA,
    /** 用户当前选择的待决。 */
    DECISION_FOCUS,
    /** 待决所在的结构化章节共识。 */
    CHAPTER_CONSENSUS,
    /** 支撑待决的讨论消息。 */
    DECISION_SOURCE_MESSAGE,
    /** 章节简报。 */
    CHAPTER_BRIEF,
    /** 章节大纲。 */
    CHAPTER_OUTLINE,
    /** 固定来源编译的人类可读章节正文生成说明。 */
    CHAPTER_GENERATION_BRIEF,
    NARRATIVE_PLAN,
    /** 正式设定。 */
    SETTING_ENTRY,
    /** 伏笔。 */
    FORESHADOWING,
    /** 章节摘要。 */
    CHAPTER_SUMMARY,
    /** 章节关键事件。 */
    CHAPTER_KEY_EVENT,
    /** 章节正文。 */
    CHAPTER_CONTENT,
    /** 已发布场景规划叶子节点。 */
    SCENE_PLAN,
    /** 同一生成批次已完成的前序场景候选。 */
    GENERATED_SCENE_DRAFT,
    /** 调用方场景目标。 */
    TARGET_TEXT,
    /** 完整历史对话轮次。 */
    CONVERSATION_TURN,
    /** 当前用户输入。 */
    USER_INPUT
}
