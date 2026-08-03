package com.dugnan.moqi.chapter.policy;

import java.util.Map;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ReplyControlRequest;
import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceModels.PreferenceDetail;
import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceModels.PreferenceRequest;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 定义回复偏好读写及会话策略解析能力。
 */
public interface ReplyPolicyPreferenceService {

    /**
     * 查询一个作用域的回复偏好。
     *
     * @param scopeType 作用域类型
     * @param scopeId 作用域 ID
     * @return 偏好，不存在时返回 null
     */
    PreferenceDetail get(String scopeType, Long scopeId);

    /**
     * 保存一个作用域的回复偏好。
     *
     * @param request 保存请求
     * @return 保存结果
     */
    PreferenceDetail save(PreferenceRequest request);

    /**
     * 清除指定作用域偏好并恢复继承策略。
     *
     * @param scopeType 作用域类型
     * @param scopeId 作用域 ID
     * @param baseVersion 当前版本
     */
    void clear(String scopeType, Long scopeId, Integer baseVersion);

    /**
     * 解析指定会话消息的最终策略。
     *
     * @param conversationId 会话 ID
     * @param content 当前消息正文
     * @param control 单次控制
     * @return 最终策略
     */
    ResolvedReplyPolicy resolve(Long conversationId, String content, ReplyControlRequest control);

    /**
     * 读取会话对应的分层深度偏好。
     *
     * @param conversationId 会话 ID
     * @return 按优先级命名的偏好
     */
    Map<String, ReplyDepth> inheritedDepths(Long conversationId);
}
