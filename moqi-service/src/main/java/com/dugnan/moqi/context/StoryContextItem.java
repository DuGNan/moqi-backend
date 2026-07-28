package com.dugnan.moqi.context;

import java.time.LocalDateTime;

/**
 * 快照中的一条模型消息及其来源元数据。
 */
public record StoryContextItem(
        StoryContextSourceType sourceType,
        String sourceId,
        String contentVersion,
        LocalDateTime sourceUpdatedAt,
        String messageRole,
        String content,
        boolean required,
        int priority,
        int order,
        int originalTokenEstimate,
        int selectedTokenEstimate,
        String selectionReason) {
}
