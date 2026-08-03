package com.dugnan.moqi.chapter.policy;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 集中定义回复策略偏好接口模型。
 */
public final class ReplyPolicyPreferenceModels {

    private ReplyPolicyPreferenceModels() {
    }

    /**
     * 保存回复深度偏好。
     *
     * @param scopeType user、work、chapter 或 conversation
     * @param scopeId user 作用域为 0，其他作用域为资源 ID
     * @param replyDepth brief、balanced 或 deep
     * @param baseVersion 更新已有偏好时的基础版本
     */
    public record PreferenceRequest(
            String scopeType,
            Long scopeId,
            String replyDepth,
            Integer baseVersion) {
    }

    /**
     * 回复深度偏好详情。
     *
     * @param id 偏好 ID
     * @param scopeType 作用域类型
     * @param scopeId 作用域 ID
     * @param replyDepth 回复深度
     * @param version 乐观锁版本
     */
    public record PreferenceDetail(
            Long id,
            String scopeType,
            Long scopeId,
            String replyDepth,
            Integer version) {
    }
}
