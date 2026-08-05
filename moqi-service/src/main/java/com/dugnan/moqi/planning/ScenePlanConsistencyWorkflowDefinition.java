package com.dugnan.moqi.planning;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.ConsistencyFinding;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 在 Agent Runtime 中执行场景规划的可恢复一致性检查步骤。
 */
@Component
public class ScenePlanConsistencyWorkflowDefinition implements AgentWorkflowDefinition {
    private static final String PRECHECK = "precheck";
    private static final String RULE_CHECK = "rule_check";
    private static final String SEMANTIC_EVALUATE = "semantic_evaluate";
    private static final String FINALIZE = "finalize";
    private final ScenePlanConsistencyServiceImpl service;

    public ScenePlanConsistencyWorkflowDefinition(ScenePlanConsistencyServiceImpl service) {
        this.service = service;
    }

    @Override public String workflowType() { return ScenePlanConsistencyServiceImpl.WORKFLOW_TYPE; }
    @Override public String startStepKey() { return PRECHECK; }
    @Override public Duration timeout() { return Duration.ofHours(1); }
    @Override public int maxAttempts(String stepKey) { return SEMANTIC_EVALUATE.equals(stepKey) ? 2 : 1; }

    @Override
    public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) {
        Long reportId = ((Number) context.input().get("reportId")).longValue();
        if (PRECHECK.equals(stepKey)) {
            service.markRunning(reportId);
            return AgentStepResult.completed(Map.of("reportId", reportId), Map.of("reportId", reportId), RULE_CHECK);
        }
        if (RULE_CHECK.equals(stepKey)) {
            List<ConsistencyFinding> findings = service.evaluateRules(reportId);
            return AgentStepResult.completed(Map.of("findingCount", findings.size()), Map.of("reportId", reportId, "findings", findings),
                    SEMANTIC_EVALUATE);
        }
        if (SEMANTIC_EVALUATE.equals(stepKey)) {
            return AgentStepResult.completed(Map.of("semanticEvaluator", "structured"), context.state(), FINALIZE);
        }
        if (FINALIZE.equals(stepKey)) {
            Object raw = context.state().get("findings");
            List<ConsistencyFinding> findings = raw == null ? List.of() : new com.fasterxml.jackson.databind.ObjectMapper()
                    .convertValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<ConsistencyFinding>>() { });
            service.completeReport(reportId, findings);
            return AgentStepResult.completed(Map.of("result", "saved"), context.state(), null);
        }
        throw new IllegalArgumentException("未知一致性检查步骤");
    }

    @Override public void applyFailure(String stepKey, AgentStepExecutionContext context, Exception exception) {
        Object value = context.input().get("reportId");
        if (value instanceof Number number) {
            service.failReport(number.longValue());
        }
    }
}
