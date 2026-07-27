package com.dugnan.moqi.llm;

import org.springframework.util.StringUtils;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 表示模型对话中的一条有序消息。
 */
public record LlmMessage(LlmRole role, String content) {

    public LlmMessage {
        if (role == null) {
            throw new IllegalArgumentException("消息角色不能为空");
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
    }
}
