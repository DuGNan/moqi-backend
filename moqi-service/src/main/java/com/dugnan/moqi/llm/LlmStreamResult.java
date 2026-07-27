package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 表示流式调用完成后的终态、元数据和安全错误。
 */
public record LlmStreamResult(
        LlmStreamStatus status,
        LlmResponseMetadata metadata,
        LlmProviderError error) {
}
