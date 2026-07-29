package com.dugnan.moqi.context;

import java.time.LocalDateTime;
import java.util.List;

import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmRole;

/**
 * 不可变的故事上下文快照。
 */
public record StoryContextSnapshot(
        Long id,
        String scopeKey,
        Long workId,
        Long chapterId,
        Long conversationId,
        StoryContextProfile profile,
        int schemaVersion,
        long snapshotVersion,
        int contextWindowTokens,
        int outputReserveTokens,
        int inputBudgetTokens,
        int estimatedInputTokens,
        String contentHash,
        List<StoryContextItem> items,
        List<StoryContextSelectionDecision> decisions,
        LocalDateTime createdAt) {

    /**
     * 固化上下文条目与选择决策列表。
     *
     * @param id 快照 ID
     * @param scopeKey 作用域键
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @param profile 上下文场景配置
     * @param schemaVersion 快照结构版本
     * @param snapshotVersion 作用域内快照版本
     * @param contextWindowTokens 上下文窗口 token 数
     * @param outputReserveTokens 输出预留 token 数
     * @param inputBudgetTokens 输入预算 token 数
     * @param estimatedInputTokens 估算输入 token 数
     * @param contentHash 内容哈希
     * @param items 上下文条目
     * @param decisions 选择决策
     * @param createdAt 创建时间
     */
    public StoryContextSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    /**
     * 按快照顺序转换为 LLM 消息列表。
     *
     * @return 不可变的 LLM 消息列表
     */
    public List<LlmMessage> toMessages() {
        return items.stream()
                .map(item -> new LlmMessage(LlmRole.valueOf(item.messageRole()), item.content()))
                .toList();
    }
}
