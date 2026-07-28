package com.dugnan.moqi.context;

/**
 * Provider 无关的 token 估算器。
 *
 * @author dgn
 */
public interface TokenEstimator {

    /**
     * 估算文本 token 数量。
     *
     * @param text 待估算文本
     * @return token 数量
     */
    int estimate(String text);

    /**
     * 在预算内确定性裁剪文本。
     *
     * @param text 原始文本
     * @param maxTokens 最大 token 数量
     * @return 裁剪后的文本
     */
    String truncate(String text, int maxTokens);
}
