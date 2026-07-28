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
    /** 章节简报。 */
    CHAPTER_BRIEF,
    /** 章节大纲。 */
    CHAPTER_OUTLINE,
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
    /** 调用方场景目标。 */
    TARGET_TEXT,
    /** 完整历史对话轮次。 */
    CONVERSATION_TURN,
    /** 当前用户输入。 */
    USER_INPUT
}
