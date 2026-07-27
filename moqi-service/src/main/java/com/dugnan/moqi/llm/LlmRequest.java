package com.dugnan.moqi.llm;

import java.util.List;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 描述与供应商无关的有序消息生成请求。
 */
public record LlmRequest(List<LlmMessage> messages, LlmOptions options) {

    public LlmRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        messages = List.copyOf(messages);
        options = options == null ? LlmOptions.defaults() : options;
    }
}
