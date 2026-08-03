package com.dugnan.moqi.chapter.policy;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 固化章节讨论单轮允许推进的主要意图与对象。
 *
 * @param primaryIntent 本轮唯一主要意图
 * @param targetType 目标类型
 * @param targetReference 目标引用摘要
 * @param allowedChanges 允许修改的范围
 * @param maxCandidates 最大候选数
 * @param allowNewTerms 是否允许引入新专有名词
 */
public record ReplyScope(
        String primaryIntent,
        String targetType,
        String targetReference,
        String allowedChanges,
        int maxCandidates,
        boolean allowNewTerms) {
}
