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
        String providerRequestId) {
}
