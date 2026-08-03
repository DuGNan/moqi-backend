package com.dugnan.moqi.chapter.policy;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 固化 conversation_reply 创建时解析出的安全策略快照。
 *
 * @param schemaVersion 输入快照结构版本
 * @param messageId 用户消息 ID
 * @param conversationId 会话 ID
 * @param replyMode 回复模式
 * @param replyDepth 回复深度
 * @param replyScope 回复范围
 * @param controlSource 控制来源
 * @param policyVersion 策略版本
 * @param contextAuthorityVersion 权威上下文规则版本
 * @param convergenceApplied 是否应用收敛反馈
 */
public record ConversationReplyTaskInputV1(
        int schemaVersion,
        Long messageId,
        Long conversationId,
        ReplyMode replyMode,
        ReplyDepth replyDepth,
        ReplyScope replyScope,
        String controlSource,
        String policyVersion,
        String contextAuthorityVersion,
        boolean convergenceApplied,
        Long continuationMessageId) {

    public static final int SCHEMA_VERSION = 1;
    public static final String AUTHORITY_VERSION = "story-context-authority-v2";

    /**
     * 从解析结果构造版本化任务输入。
     *
     * @param messageId 用户消息 ID
     * @param conversationId 会话 ID
     * @param policy 最终回复策略
     * @return 任务输入快照
     */
    public static ConversationReplyTaskInputV1 from(
            Long messageId,
            Long conversationId,
            ResolvedReplyPolicy policy) {
        return new ConversationReplyTaskInputV1(
                SCHEMA_VERSION,
                messageId,
                conversationId,
                policy.mode(),
                policy.depth(),
                policy.scope(),
                policy.controlSource(),
                policy.policyVersion(),
                AUTHORITY_VERSION,
                policy.convergenceApplied(),
                null);
    }

    /**
     * 从解析结果构造带继续展开锚点的任务输入。
     *
     * @param messageId 用户消息 ID
     * @param conversationId 会话 ID
     * @param policy 最终回复策略
     * @param continuationMessageId 被继续展开的助手消息 ID
     * @return 任务输入快照
     */
    public static ConversationReplyTaskInputV1 from(
            Long messageId,
            Long conversationId,
            ResolvedReplyPolicy policy,
            Long continuationMessageId) {
        return new ConversationReplyTaskInputV1(
                SCHEMA_VERSION,
                messageId,
                conversationId,
                policy.mode(),
                policy.depth(),
                policy.scope(),
                policy.controlSource(),
                policy.policyVersion(),
                AUTHORITY_VERSION,
                policy.convergenceApplied(),
                continuationMessageId);
    }
}
