package com.dugnan.moqi.chapter.policy;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 表示服务端最终解析并可持久化的章节讨论回复策略。
 *
 * @param mode 回复模式
 * @param depth 回复深度
 * @param scope 本轮推进范围
 * @param controlSource 深度控制来源
 * @param policyVersion 策略版本
 * @param convergenceApplied 是否应用用户收敛反馈
 */
public record ResolvedReplyPolicy(
        ReplyMode mode,
        ReplyDepth depth,
        ReplyScope scope,
        String controlSource,
        String policyVersion,
        boolean convergenceApplied) {
}
