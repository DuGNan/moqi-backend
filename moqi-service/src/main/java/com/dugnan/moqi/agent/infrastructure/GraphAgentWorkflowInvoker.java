package com.dugnan.moqi.agent.infrastructure;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig.node_async;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;

/**
 * Spring AI Alibaba Graph 的唯一生产适配点。
 * 持久化 checkpoint 仍由 Agent Runtime 写入稳定 JSON，避免 Graph 对象进入数据库。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 隔离 Graph 框架并执行单个框架无关工作流步骤。
 */
@Component
public class GraphAgentWorkflowInvoker {

    private static final String EXECUTE_STEP = "executeStep";
    private static final String RESULT = "result";

    public AgentStepResult invoke(
            AgentWorkflowDefinition definition,
            String stepKey,
            AgentStepExecutionContext context) throws Exception {
        AtomicReference<AgentStepResult> result = new AtomicReference<>();
        StateGraph graph = new StateGraph(keyStrategies())
                .addNode(EXECUTE_STEP, node_async((state, config) -> {
                    try {
                        AgentStepResult execution = definition.execute(stepKey, context);
                        result.set(execution);
                        return Map.of(RESULT, "completed");
                    } catch (Exception exception) {
                        throw new IllegalStateException("Agent 步骤执行失败", exception);
                    }
                }))
                .addEdge(START, EXECUTE_STEP)
                .addEdge(EXECUTE_STEP, END);
        try {
            graph.compile(CompileConfig.builder().build())
                    .stream(Map.of(), RunnableConfig.builder().threadId("agent-run:" + context.runId()).build())
                    .collectList()
                    .block();
        } catch (GraphStateException exception) {
            throw new IllegalStateException("Graph 工作流编排失败", exception);
        }
        AgentStepResult execution = result.get();
        if (execution == null) {
            throw new IllegalStateException("Graph 工作流未返回步骤结果");
        }
        return execution;
    }

    private KeyStrategyFactory keyStrategies() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>(1);
            strategies.put(RESULT, (previous, current) -> current);
            return strategies;
        };
    }
}
