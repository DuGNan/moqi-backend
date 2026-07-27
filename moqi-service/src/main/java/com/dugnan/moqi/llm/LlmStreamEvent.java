package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 定义流式调用中的文本、元数据和完成事件。
 */
public sealed interface LlmStreamEvent {

    record TextDelta(String text) implements LlmStreamEvent {
    }

    record Metadata(LlmResponseMetadata metadata) implements LlmStreamEvent {
    }

    record Completed(LlmResponseMetadata metadata) implements LlmStreamEvent {
    }
}
