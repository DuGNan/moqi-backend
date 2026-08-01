package com.dugnan.moqi.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.agent.AgentRunCallRegistry;
import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 验证框架无关工作流可经生产 Graph 适配层执行。
 */
class GraphAgentWorkflowInvokerTest {

    @Test
    void invokesFrameworkNeutralWorkflowThroughGraph() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AgentWorkflowDefinition workflow = new AgentWorkflowDefinition() {
            @Override
            public String workflowType() {
                return "fake";
            }

            @Override
            public String startStepKey() {
                return "draft";
            }

            @Override
            public Duration timeout() {
                return Duration.ofMinutes(1);
            }

            @Override
            public int maxAttempts(String stepKey) {
                return 2;
            }

            @Override
            public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) {
                calls.incrementAndGet();
                return AgentStepResult.completed(Map.of("summary", "ok"), Map.of("state", "saved"), null);
            }
        };
        AgentStepExecutionContext context = new AgentStepExecutionContext(
                43L, 1L, "draft", 1, "43:draft", Map.of(), Map.of(), Map.of(), new AgentRunCallRegistry());

        AgentStepResult result = new GraphAgentWorkflowInvoker().invoke(workflow, "draft", context);

        assertThat(calls).hasValue(1);
        assertThat(result.outputSummary()).containsEntry("summary", "ok");
        assertThat(result.checkpointState()).containsEntry("state", "saved");
    }
}
