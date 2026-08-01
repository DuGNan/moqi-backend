package com.dugnan.moqi.agent.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * Agent Run 的单个稳定步骤及尝试记录。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 映射 Agent Run 中一个稳定步骤键的单次执行尝试。
 */
@Data
@TableName("agent_run_steps")
public class AgentRunStepEntity extends BaseEntity {

    private Long runId;
    private String stepKey;
    private Integer attempt;
    private String stepStatus;
    private String inputSummaryJson;
    private String outputSummaryJson;
    private String errorCategory;
    private String errorCode;
    private String errorMessage;
    private String modelCallRef;
    private Integer retryable;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
