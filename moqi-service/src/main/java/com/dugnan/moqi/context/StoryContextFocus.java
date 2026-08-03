package com.dugnan.moqi.context;

import java.util.List;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 表示由服务端校验后注入故事上下文引擎的待决对焦资料。
 */
public record StoryContextFocus(
        Long briefId,
        Integer briefVersion,
        String decisionKey,
        String decisionStatus,
        String decisionContent,
        String consensusContent,
        List<StoryContextFocusSource> sources) {

    /**
     * 防止调用方修改来源列表。
     *
     * @param briefId Brief ID
     * @param briefVersion Brief 版本
     * @param decisionKey 待决键
     * @param decisionStatus 决策状态
     * @param decisionContent 待决内容
     * @param consensusContent 结构化共识内容
     * @param sources 待决来源列表
     */
    public StoryContextFocus {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * 保留未显式传递决策状态的兼容构造入口。
     */
    public StoryContextFocus(
            Long briefId,
            Integer briefVersion,
            String decisionKey,
            String decisionContent,
            String consensusContent,
            List<StoryContextFocusSource> sources) {
        this(briefId, briefVersion, decisionKey, "candidate", decisionContent, consensusContent, sources);
    }

    /**
     * 一条待决来源消息。
     *
     * @param messageId 消息 ID
     * @param messageRole 消息角色
     * @param content 消息正文
     */
    public record StoryContextFocusSource(Long messageId, String messageRole, String content) {
    }
}
