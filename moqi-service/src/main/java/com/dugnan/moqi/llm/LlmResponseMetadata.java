package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 汇总模型调用的供应商、终态和 token 用量元数据。
 */
public record LlmResponseMetadata(
        String provider,
        String model,
        String finishReason,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        String providerRequestId,
        Long modelCallId) {

    /**
     * 保留 Provider 层不感知持久化调用 ID 的构造入口。
     */
    public LlmResponseMetadata(
            String provider,
            String model,
            String finishReason,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            String providerRequestId) {
        this(provider, model, finishReason, inputTokens, outputTokens, totalTokens, providerRequestId, null);
    }
}
