package com.dugnan.moqi.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * Agent Run 的稳定状态快照。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 映射可版本化且可校验的 Agent checkpoint 持久化记录。
 */
@Data
@TableName("agent_checkpoints")
public class AgentCheckpointEntity extends BaseEntity {

    private Long runId;
    private Long stepId;
    private Long sequenceId;
    private Integer schemaVersion;
    private String stepKey;
    private String nextStepKey;
    private String checkpointStatus;
    private String stateJson;
    private String stateHash;
}
