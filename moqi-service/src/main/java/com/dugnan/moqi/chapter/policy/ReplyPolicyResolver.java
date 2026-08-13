package com.dugnan.moqi.chapter.policy;

import java.util.Map;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ReplyControlRequest;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 根据单次消息与分层偏好纯计算最终回复策略。
 */
public interface ReplyPolicyResolver {

    /**
     * 解析最终回复策略。
     *
     * @param content 当前用户消息
     * @param control 单次控制
     * @param inheritedDepths 按 conversation、chapter、work、user 顺序提供的偏好
     * @return 最终回复策略
     */
    ResolvedReplyPolicy resolve(
            String content,
            ReplyControlRequest control,
            Map<String, ReplyDepth> inheritedDepths);

    /**
     * 结合最近一轮回复特征解析最终回复策略。
     *
     * @param content 当前用户消息
     * @param control 单次控制
     * @param inheritedDepths 分层深度偏好
     * @param signals 最近回复信号
     * @return 最终回复策略
     */
    default ResolvedReplyPolicy resolve(
            String content,
            ReplyControlRequest control,
            Map<String, ReplyDepth> inheritedDepths,
            ReplyConversationSignals signals) {
        return resolve(content, control, inheritedDepths);
    }
}
