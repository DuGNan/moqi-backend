package com.dugnan.moqi.agent.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * Agent Run 的人工确认中断记录。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 映射人工中断、一次性恢复令牌和确认响应。
 */
@Data
@TableName("agent_interruptions")
public class AgentInterruptionEntity extends BaseEntity {

    private Long runId;
    private Long checkpointId;
    private Long stepId;
    private String interruptionType;
    private String interruptionStatus;
    private String resumeTokenHash;
    private Integer tokenVersion;
    private String requestJson;
    private String responseJson;
    private String responseHash;
    private LocalDateTime expiresAt;
    private LocalDateTime resumedAt;
}
