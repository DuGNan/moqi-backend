package com.dugnan.moqi.agent;

import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.entity.AgentRunEntity;
import com.dugnan.moqi.agent.mapper.AgentRunMapper;
import com.dugnan.moqi.task.event.AiTaskCancellationSignal;

/**
 * 将 AI Task 的取消信号传播给关联 Agent Run。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 将既有 AI Task 的取消信号桥接到关联 Agent Run。
 */
@Component
public class AgentRunCancellationBridge {

    private final AgentRunMapper runMapper;
    private final AgentRuntime runtime;

    public AgentRunCancellationBridge(AgentRunMapper runMapper, AgentRuntime runtime) {
        this.runMapper = runMapper;
        this.runtime = runtime;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void cancel(AiTaskCancellationSignal signal) {
        AgentRunEntity run = runMapper.findByAiTaskId(signal.taskId());
        if (run != null) {
            runtime.cancel(run.getId());
        }
    }
}
