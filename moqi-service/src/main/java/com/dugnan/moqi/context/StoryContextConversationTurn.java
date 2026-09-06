package com.dugnan.moqi.context;

/**
 * @author dgn
 * @date 2026-09-04
 * @description 固化一个完整的作者与助手历史对话轮次。
 */
public record StoryContextConversationTurn(String userContent, String assistantContent) {
}
