package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 映射当前用户在不同创作作用域下的回复深度偏好。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("reply_policy_preferences")
public class ReplyPolicyPreferenceEntity extends BaseEntity {

    private String userId;
    private String scopeType;
    private Long scopeId;
    private String replyDepth;
}
