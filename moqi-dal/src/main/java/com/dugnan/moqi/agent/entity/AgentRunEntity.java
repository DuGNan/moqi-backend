package com.dugnan.moqi.agent.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * Agent Runtime 的一次可恢复执行记录。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 映射一次可恢复 Agent Run 的当前状态和业务归属。
 */
@Data
@TableName("agent_runs")
public class AgentRunEntity extends BaseEntity {

    private String userId;
    private Long workId;
    private Long chapterId;
    private Long aiTaskId;
    private String workflowType;
    private String idempotencyKey;
    private Long inputSnapshotVersion;
    private String inputJson;
    private String inputHash;
    private String runStatus;
    private String currentStepKey;
    private Long checkpointSequence;
    private LocalDateTime timeoutAt;
    private String errorCode;
    private String errorMessage;
}
