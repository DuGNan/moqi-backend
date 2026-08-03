package com.dugnan.moqi.chapter.focus;

import java.util.List;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 表示服务端按持久化引用解析出的受控讨论对焦上下文。
 */
public record ResolvedDiscussionFocus(
        Long briefId,
        Integer briefVersion,
        String decisionKey,
        String decisionStatus,
        String decisionTitle,
        String decisionPrompt,
        String candidateSummary,
        String consensusContent,
        List<DiscussionFocusSource> sources) {

    /**
     * 防止调用方修改来源列表。
     *
     * @param briefId Brief ID
     * @param briefVersion Brief 版本
     * @param decisionKey 待决键
     * @param decisionStatus 决策状态
     * @param decisionTitle 待决标题
     * @param decisionPrompt 待决提示
     * @param candidateSummary 候选项摘要
     * @param consensusContent 结构化共识内容
     * @param sources 讨论来源列表
     */
    public ResolvedDiscussionFocus {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * 保留未显式传递决策状态的兼容构造入口。
     */
    public ResolvedDiscussionFocus(
            Long briefId,
            Integer briefVersion,
            String decisionKey,
            String decisionTitle,
            String decisionPrompt,
            String candidateSummary,
            String consensusContent,
            List<DiscussionFocusSource> sources) {
        this(briefId, briefVersion, decisionKey, "candidate", decisionTitle, decisionPrompt,
                candidateSummary, consensusContent, sources);
    }

    /**
     * 待决引用的一条讨论来源。
     *
     * @param messageId 消息 ID
     * @param messageRole 消息角色
     * @param content 消息正文
     */
    public record DiscussionFocusSource(Long messageId, String messageRole, String content) {
    }
}
