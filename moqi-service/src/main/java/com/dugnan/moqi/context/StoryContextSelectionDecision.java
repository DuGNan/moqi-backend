package com.dugnan.moqi.context;

/**
 * 未进入模型的候选项，保留可观察的选择原因但不保留正文。
 */
public record StoryContextSelectionDecision(
        StoryContextSourceType sourceType,
        String sourceId,
        int estimatedTokens,
        String reason) {
}
