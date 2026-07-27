package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 描述与厂商无关的文本生成请求。
 */
public record LlmRequest(String systemPrompt, String userPrompt, Integer maxTokens) {
}
