package com.dugnan.moqi.agent;

import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.dugnan.moqi.agent.event.AgentRunSubmittedEvent;

/**
 * 在事务提交后把 Run 投递至独立有界执行器。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 在事务提交后将 Agent Run 投递给专属有界执行器。
 */
@Component
public class AgentRunDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunDispatcher.class);

    private final ThreadPoolTaskExecutor executor;
    private final AgentRuntimeService runtime;

    public AgentRunDispatcher(
            @Qualifier("agentRuntimeExecutor") ThreadPoolTaskExecutor executor,
            AgentRuntimeService runtime) {
        this.executor = executor;
        this.runtime = runtime;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void dispatch(AgentRunSubmittedEvent event) {
        try {
            executor.execute(() -> {
                try {
                    runtime.executeQueuedRun(event.runId());
                } catch (RuntimeException exception) {
                    LOGGER.error("Agent Run 派发执行失败，runId={}", event.runId(), exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            runtime.rejectExecution(event.runId());
        }
    }
}
