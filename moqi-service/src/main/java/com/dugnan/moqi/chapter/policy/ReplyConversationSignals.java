package com.dugnan.moqi.chapter.policy;

/**
 * 描述最近一轮讨论对当前回复策略有影响的有限信号。
 *
 * @param previousMode 上一轮助手回复模式
 * @param previousAssistantAskedQuestion 上一轮是否以问题推进
 * @param previousAssistantOfferedOptions 上一轮是否提供了候选选项
 */
public record ReplyConversationSignals(
        ReplyMode previousMode,
        boolean previousAssistantAskedQuestion,
        boolean previousAssistantOfferedOptions) {

    public static ReplyConversationSignals empty() {
        return new ReplyConversationSignals(null, false, false);
    }
}
