package com.dugnan.moqi.config.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 映射当前用户的白名单配置数据。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("user_configs")
public class UserConfigEntity extends BaseEntity {

    private String userId;

    private String configKey;

    private String configValue;
}
