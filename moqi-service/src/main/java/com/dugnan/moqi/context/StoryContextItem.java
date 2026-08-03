package com.dugnan.moqi.context;

import java.time.LocalDateTime;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 保存一条模型消息的来源、预算选择和权威状态元数据。
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
        String selectionReason,
        StoryContextAuthorityStatus authorityStatus) {

    /**
     * 兼容创建未显式声明权威状态的旧条目。
     */
    public StoryContextItem(
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
        this(sourceType, sourceId, contentVersion, sourceUpdatedAt, messageRole, content, required,
                priority, order, originalTokenEstimate, selectedTokenEstimate, selectionReason,
                StoryContextAuthorityStatus.EVIDENCE);
    }

    /**
     * 旧快照缺失 authorityStatus 时按证据降级，绝不自动升级为权威事实。
     */
    public StoryContextItem {
        authorityStatus = authorityStatus == null ? StoryContextAuthorityStatus.EVIDENCE : authorityStatus;
    }
}
