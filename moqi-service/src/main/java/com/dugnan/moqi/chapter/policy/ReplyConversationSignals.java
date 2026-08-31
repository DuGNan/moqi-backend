package com.dugnan.moqi.chapter.policy;

/**
 * 描述最近一轮讨论对当前回复策略有影响的有限信号。
 *
 * @param previousMode 上一轮助手回复模式
 * @param previousAssistantAskedQuestion 上一轮是否以问题推进
 * @param previousAssistantOfferedOptions 上一轮是否提供了候选选项
 * @param deferredDepth 上一轮澄清前暂存的目标深度
 * @param directClarificationAnswer 当前消息是否直接回答上一轮澄清
 */
public record ReplyConversationSignals(
        ReplyMode previousMode,
        boolean previousAssistantAskedQuestion,
        boolean previousAssistantOfferedOptions,
        ReplyDepth deferredDepth,
        boolean directClarificationAnswer) {

    public ReplyConversationSignals(
            ReplyMode previousMode,
            boolean previousAssistantAskedQuestion,
            boolean previousAssistantOfferedOptions) {
        this(previousMode, previousAssistantAskedQuestion, previousAssistantOfferedOptions, null, false);
    }

    public static ReplyConversationSignals empty() {
        return new ReplyConversationSignals(null, false, false, null, false);
    }
}
