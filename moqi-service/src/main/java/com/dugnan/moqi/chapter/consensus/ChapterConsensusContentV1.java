package com.dugnan.moqi.chapter.consensus;

import java.util.List;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 定义章节结构化共识 V1 的持久化契约。
 */
public record ChapterConsensusContentV1(
        Integer schemaVersion,
        String chapterTask,
        StateChange stateChange,
        String keyPush,
        ReaderProgress readerProgress,
        List<String> writingBoundaries,
        List<Decision> decisions) {

    /**
     * 描述章节开始与结束时的关键状态变化。
     *
     * @param from 章节开始状态
     * @param to 章节结束状态
     */
    public record StateChange(String from, String to) {
    }

    /**
     * 描述读者在本章获得的回报和保留的问题。
     *
     * @param payoff 本章兑现的阅读回报
     * @param openQuestion 本章结束后保留的问题
     */
    public record ReaderProgress(String payoff, String openQuestion) {
    }

    /**
     * 描述一个需要在共创过程中确认或继续讨论的决策。
     *
     * @param key 稳定决策键
     * @param title 决策标题
     * @param status 决策状态
     * @param required 是否必须在确认 Brief 前解决
     * @param prompt 待讨论问题
     * @param candidateSummary 当前候选或确认结论摘要
     * @param sourceMessageIds 支撑结论的讨论消息 ID
     */
    public record Decision(
            String key,
            String title,
            String status,
            boolean required,
            String prompt,
            String candidateSummary,
            List<Long> sourceMessageIds) {
    }
}
