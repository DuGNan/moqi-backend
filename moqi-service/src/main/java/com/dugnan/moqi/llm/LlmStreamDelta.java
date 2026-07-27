package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 描述模型流式返回的一段新增文本。
 */
public record LlmStreamDelta(String text) {
}
