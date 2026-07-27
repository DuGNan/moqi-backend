package com.dugnan.moqi.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 描述供应商无关的文本或结构化模型结果及元数据。
 */
public record LlmResponse(
        String content,
        JsonNode structuredContent,
        LlmResponseMetadata metadata) {
}
