package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 描述 Provider 当前真实接入的能力与模型限制。
 */
public record LlmProviderCapabilities(
        boolean streaming,
        boolean structuredOutput,
        boolean toolCalling,
        Integer maxContextTokens,
        Integer maxOutputTokens) {
}
