package com.dugnan.moqi.agent.event;

import java.time.LocalDateTime;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 承载供章节 SSE 转换的引用型 Agent Run 生命周期事件。
 */
public record AgentRunEvent(
        String type,
        Long chapterId,
        Long runId,
        String workflowType,
        Long aiTaskId,
        String runStatus,
        Long stepId,
        String stepKey,
        String stepStatus,
        Long checkpointSequence,
        Long interruptionId,
        LocalDateTime updatedAt) {

    public static AgentRunEvent updated(
            Long chapterId,
            Long runId,
            String workflowType,
            Long aiTaskId,
            String runStatus,
            Long stepId,
            String stepKey,
            String stepStatus,
            Long checkpointSequence,
            Long interruptionId) {
        return new AgentRunEvent(
                "agent_run.updated", chapterId, runId, workflowType, aiTaskId, runStatus,
                stepId, stepKey, stepStatus, checkpointSequence, interruptionId, LocalDateTime.now());
    }
}
