package com.dugnan.moqi.context;

/**
 * 创建故事上下文快照的应用层命令。
 */
public record StoryContextBuildCommand(
        StoryContextProfile profile,
        Long workId,
        Long chapterId,
        Long conversationId,
        Long currentMessageId,
        String taskInstruction,
        String currentInput,
        String targetText,
        int contextWindowTokens,
        int outputReserveTokens,
        StoryContextFocus discussionFocus) {

    /**
     * 保留不含讨论对焦的旧构造入口。
     */
    public StoryContextBuildCommand(
            StoryContextProfile profile,
            Long workId,
            Long chapterId,
            Long conversationId,
            Long currentMessageId,
            String taskInstruction,
            String currentInput,
            String targetText,
            int contextWindowTokens,
            int outputReserveTokens) {
        this(
                profile,
                workId,
                chapterId,
                conversationId,
                currentMessageId,
                taskInstruction,
                currentInput,
                targetText,
                contextWindowTokens,
                outputReserveTokens,
                null);
    }

    public StoryContextBuildCommand {
        if (profile == null || workId == null || workId <= 0
                || contextWindowTokens <= 0 || outputReserveTokens < 0) {
            throw new IllegalArgumentException("上下文命令参数不合法");
        }
        if (chapterId != null && chapterId <= 0) {
            throw new IllegalArgumentException("chapterId 必须为正数");
        }
        if (conversationId != null && conversationId <= 0) {
            throw new IllegalArgumentException("conversationId 必须为正数");
        }
        if (currentMessageId != null && currentMessageId <= 0) {
            throw new IllegalArgumentException("currentMessageId 必须为正数");
        }
    }

    public int inputBudgetTokens() {
        return contextWindowTokens - outputReserveTokens;
    }
}
