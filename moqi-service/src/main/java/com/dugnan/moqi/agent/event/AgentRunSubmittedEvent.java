package com.dugnan.moqi.agent.event;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 表示事务提交后可安全投递的 Agent Run 调度信号。
 */
public record AgentRunSubmittedEvent(Long runId) {
}
