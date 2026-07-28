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

    public StoryContextSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public List<LlmMessage> toMessages() {
        return items.stream()
                .map(item -> new LlmMessage(LlmRole.valueOf(item.messageRole()), item.content()))
                .toList();
    }
}
