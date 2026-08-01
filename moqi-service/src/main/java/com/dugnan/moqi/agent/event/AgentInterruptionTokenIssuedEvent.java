package com.dugnan.moqi.agent.event;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 在进程内向可信适配层交付一次性人工恢复令牌。
 */
public record AgentInterruptionTokenIssuedEvent(Long runId, Long interruptionId, String resumeToken, int tokenVersion) {

    @Override
    public String toString() {
        return "AgentInterruptionTokenIssuedEvent[runId=" + runId
                + ", interruptionId=" + interruptionId
                + ", resumeToken=***, tokenVersion=" + tokenVersion + "]";
    }
}
