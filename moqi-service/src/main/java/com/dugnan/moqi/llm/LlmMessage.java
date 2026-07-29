package com.dugnan.moqi.llm;

import org.springframework.util.StringUtils;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 表示模型对话中的一条有序消息。
 */
public record LlmMessage(LlmRole role, String content) {

    /**
     * 校验消息角色与非空内容。
     *
     * @param role 消息角色
     * @param content 消息内容
     * @throws IllegalArgumentException 角色为空或内容为空白
     */
    public LlmMessage {
        if (role == null) {
            throw new IllegalArgumentException("消息角色不能为空");
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
    }
}
