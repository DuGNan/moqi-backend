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
        StoryContextFocus discussionFocus,
        SceneGenerationContextFocus sceneGenerationFocus,
        MessageReference messageReference) {

    public StoryContextBuildCommand(
            StoryContextProfile profile, Long workId, Long chapterId, Long conversationId,
            Long currentMessageId, String taskInstruction, String currentInput, String targetText,
            int contextWindowTokens, int outputReserveTokens, StoryContextFocus discussionFocus,
            SceneGenerationContextFocus sceneGenerationFocus) {
        this(profile, workId, chapterId, conversationId, currentMessageId, taskInstruction, currentInput,
                targetText, contextWindowTokens, outputReserveTokens, discussionFocus, sceneGenerationFocus, null);
    }

    /**
     * 保留不含讨论对焦的旧构造入口。
     *
     * @param profile 上下文场景配置
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @param currentMessageId 当前消息 ID
     * @param taskInstruction 当前任务指令
     * @param currentInput 当前用户输入
     * @param targetText 当前目标文本
     * @param contextWindowTokens 上下文窗口 token 数
     * @param outputReserveTokens 输出预留 token 数
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
                null,
                null,
                null);
    }

    /**
     * 保留仅包含讨论对焦资料的兼容构造入口。
     *
     * @param profile 上下文场景配置
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @param currentMessageId 当前消息 ID
     * @param taskInstruction 当前任务指令
     * @param currentInput 当前用户输入
     * @param targetText 当前目标文本
     * @param contextWindowTokens 上下文窗口 token 数
     * @param outputReserveTokens 输出预留 token 数
     * @param discussionFocus 讨论对焦资料
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
            int outputReserveTokens,
            StoryContextFocus discussionFocus) {
        this(profile, workId, chapterId, conversationId, currentMessageId, taskInstruction, currentInput,
                targetText, contextWindowTokens, outputReserveTokens, discussionFocus, null, null);
    }

    /**
     * 校验上下文命令的必填标识与 token 预算。
     *
     * @param profile 上下文场景配置
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @param currentMessageId 当前消息 ID
     * @param taskInstruction 当前任务指令
     * @param currentInput 当前用户输入
     * @param targetText 当前目标文本
     * @param contextWindowTokens 上下文窗口 token 数
     * @param outputReserveTokens 输出预留 token 数
     * @param discussionFocus 讨论对焦资料
     * @throws IllegalArgumentException 标识或 token 预算不合法
     */
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

    /**
     * 计算可用于输入上下文的 token 预算。
     *
     * @return 输入预算 token 数
     */
    public int inputBudgetTokens() {
        return contextWindowTokens - outputReserveTokens;
    }
}
