package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dugnan.moqi.common.entity.BaseEntity;
import lombok.Data;

/**
 * @author dgn
 * @date 2026-10-25
 * @description 保存正文会话每个阶段的真实模型请求、输出与校验诊断。
 */
@Data
@TableName("prose_execution_calls")
public class ProseExecutionCallEntity extends BaseEntity {
    private Long assistanceId;
    private String stage;
    private String messagesJson;
    private String optionsJson;
    private String rawOutput;
    private String metadataJson;
    private String validationError;
    private String validationStatus;
    private Long repairOfId;
}
