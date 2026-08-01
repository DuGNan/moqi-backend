package com.dugnan.moqi.agent.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.dugnan.moqi.agent.AgentRunCallRegistry;

/**
 * Agent Runtime 的框架无关传输模型。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 汇总 Agent Runtime 的命令、视图和步骤执行传输模型。
 */
public final class AgentRuntimeModels {

    private AgentRuntimeModels() {
    }

    public record StartAgentRunCommand(
            String userId,
            Long workId,
            Long chapterId,
            String workflowType,
            String idempotencyKey,
            Long inputSnapshotVersion,
            Map<String, Object> input,
            Long aiTaskId) {
    }

    public record ResumeAgentRunCommand(
            Long runId,
            String resumeToken,
            Integer tokenVersion,
            Map<String, Object> confirmation) {
    }

    public record RetryAgentStepCommand(Long runId, String stepKey, Integer expectedAttempt) {
    }

    public record AgentResumeToken(
            Long runId,
            Long interruptionId,
            String resumeToken,
            Integer tokenVersion) {

        @Override
        public String toString() {
            return "AgentResumeToken[runId=" + runId
                    + ", interruptionId=" + interruptionId
                    + ", resumeToken=***, tokenVersion=" + tokenVersion + "]";
        }
    }

    public record AgentRunView(
            Long runId,
            String workflowType,
            String runStatus,
            Long workId,
            Long chapterId,
            Long aiTaskId,
            String currentStepKey,
            Long checkpointSequence,
            Long interruptionId,
            Integer interruptionTokenVersion,
            LocalDateTime timeoutAt,
            String errorCode,
            String errorMessage) {
    }

    public record AgentStepExecutionContext(
            Long runId,
            Long stepId,
            String stepKey,
            int attempt,
            String effectKey,
            Map<String, Object> input,
            Map<String, Object> state,
            Map<String, Object> humanResponse,
            AgentRunCallRegistry callRegistry) {
    }

    public record AgentStepResult(
            Map<String, Object> outputSummary,
            Map<String, Object> checkpointState,
            String nextStepKey,
            String modelCallRef,
            AgentInterruptionRequest interruption) {

        public static AgentStepResult completed(
                Map<String, Object> outputSummary,
                Map<String, Object> checkpointState,
                String nextStepKey) {
            return new AgentStepResult(outputSummary, checkpointState, nextStepKey, null, null);
        }
    }

    public record AgentInterruptionRequest(
            String interruptionType,
            Map<String, Object> request,
            LocalDateTime expiresAt) {
    }
}
