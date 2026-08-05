package com.dugnan.moqi.chapter.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationFinding;
import com.dugnan.moqi.chapter.service.impl.GenerationEvaluationServiceImpl;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmCallContext;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmRole;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 在 Agent Runtime 中执行可恢复的正文一致性评价步骤。
 */
@Component
public class GenerationEvaluationWorkflowDefinition implements AgentWorkflowDefinition {

    private static final String PRECHECK = "precheck";
    private static final String RULE_CHECK = "rule_check";
    private static final String SEMANTIC_EVALUATE = "semantic_evaluate";
    private static final String REVISE_CANDIDATE = "revise_candidate";
    private static final String RE_EVALUATE = "re_evaluate";
    private static final String FINALIZE = "finalize";
    private final GenerationEvaluationServiceImpl evaluationService;
    private final LlmProviderFactory providerFactory;
    private final UserConfigService userConfigService;
    private final ObjectMapper objectMapper;

    public GenerationEvaluationWorkflowDefinition(GenerationEvaluationServiceImpl evaluationService,
            LlmProviderFactory providerFactory, UserConfigService userConfigService, ObjectMapper objectMapper) {
        this.evaluationService = evaluationService;
        this.providerFactory = providerFactory;
        this.userConfigService = userConfigService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String workflowType() {
        return GenerationEvaluationServiceImpl.WORKFLOW_TYPE;
    }

    @Override
    public String startStepKey() {
        return PRECHECK;
    }

    @Override
    public Duration timeout() {
        return Duration.ofHours(1);
    }

    @Override
    public int maxAttempts(String stepKey) {
        return SEMANTIC_EVALUATE.equals(stepKey) || REVISE_CANDIDATE.equals(stepKey) || RE_EVALUATE.equals(stepKey) ? 2 : 1;
    }

    @Override
    public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) {
        Long reportId = reportId(context);
        if (PRECHECK.equals(stepKey)) {
            evaluationService.markRunning(reportId);
            return AgentStepResult.completed(Map.of("reportId", reportId), Map.of("reportId", reportId), RULE_CHECK);
        }
        if (RULE_CHECK.equals(stepKey)) {
            List<EvaluationFinding> findings = evaluationService.deterministicFindings(reportId);
            return AgentStepResult.completed(Map.of("findingCount", findings.size()), Map.of("reportId", reportId, "findings", findings),
                    SEMANTIC_EVALUATE);
        }
        if (SEMANTIC_EVALUATE.equals(stepKey)) {
            List<EvaluationFinding> semantic = evaluate(reportId, context);
            List<EvaluationFinding> rules = objectMapper.convertValue(context.state().get("findings"), new TypeReference<>() { });
            List<EvaluationFinding> merged = new java.util.ArrayList<>(rules == null ? List.of() : rules);
            merged.addAll(semantic);
            return AgentStepResult.completed(Map.of("semanticFindingCount", semantic.size()),
                    Map.of("reportId", reportId, "findings", List.copyOf(merged)),
                    evaluationService.shouldRevise(reportId, merged) ? REVISE_CANDIDATE : FINALIZE);
        }
        if (REVISE_CANDIDATE.equals(stepKey)) {
            List<EvaluationFinding> findings = objectMapper.convertValue(context.state().get("findings"), new TypeReference<>() { });
            evaluationService.persistRevision(reportId, findings, revise(reportId, findings, context));
            return AgentStepResult.completed(Map.of("revisionCreated", true), context.state(), RE_EVALUATE);
        }
        if (RE_EVALUATE.equals(stepKey)) {
            List<EvaluationFinding> findings = evaluateSource(reportId, context, evaluationService.revisedSemanticSource(reportId));
            return AgentStepResult.completed(Map.of("reEvaluationFindingCount", findings.size()),
                    Map.of("reportId", reportId, "findings", findings), FINALIZE);
        }
        if (FINALIZE.equals(stepKey)) {
            Object value = context.state().get("findings");
            List<EvaluationFinding> findings = value == null ? List.of() : objectMapper.convertValue(value, new TypeReference<>() { });
            evaluationService.complete(reportId, findings);
            return AgentStepResult.completed(Map.of("reportId", reportId), context.state(), null);
        }
        throw new IllegalArgumentException("未知正文评价步骤");
    }

    @Override
    public void applyFailure(String stepKey, AgentStepExecutionContext context, Exception exception) {
        evaluationService.fail(reportId(context));
    }

    private Long reportId(AgentStepExecutionContext context) {
        Object value = context.input().get("reportId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Agent Run 缺少 reportId");
    }

    private List<EvaluationFinding> evaluate(Long reportId, AgentStepExecutionContext context) {
        return evaluateSource(reportId, context, evaluationService.semanticSource(reportId));
    }

    private List<EvaluationFinding> evaluateSource(Long reportId, AgentStepExecutionContext context, String source) {
        try {
            LlmExecutionConfig config = userConfigService.requireAvailableExecutionConfig();
            LlmProvider provider = providerFactory.createObserved(config, LlmCallContext.builder(workflowType(), SEMANTIC_EVALUATE)
                    .workId(context.input().get("workId") instanceof Number number ? number.longValue() : null)
                    .aiTaskId(context.input().get("aiTaskId") instanceof Number number ? number.longValue() : null)
                    .agentRunId(context.runId()).agentStepId(context.stepId()).logicalCallId("agent-step:" + context.stepId() + ":evaluation")
                    .promptTemplateVersion("generation-evaluator-v1").sourceFingerprint(evaluationService.sourceFingerprint(reportId)).build());
            LlmResponse response = provider.generate(new LlmRequest(List.of(
                    new LlmMessage(LlmRole.SYSTEM, "仅输出 JSON 对象 {\"findings\":[]}。Finding 只能描述评价，不得生成或确认正文。"),
                    new LlmMessage(LlmRole.USER, source)),
                    new LlmOptions(2048, null, List.of(), LlmResponseFormat.JSON_OBJECT)));
            JsonNode content = response == null ? null : response.structuredContent();
            if (content == null || !content.isObject() || content.size() != 1 || !content.has("findings") || !content.get("findings").isArray()) {
                throw new IllegalArgumentException("模型评价未返回合法结构化 Finding");
            }
            List<EvaluationFinding> findings = objectMapper.convertValue(content.get("findings"), new TypeReference<>() { });
            return evaluationService.validateSemanticFindings(reportId, findings);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("正文语义评价 Provider 调用失败", exception);
        }
    }

    private String revise(Long reportId, List<EvaluationFinding> findings, AgentStepExecutionContext context) {
        try {
            LlmExecutionConfig config = userConfigService.requireAvailableExecutionConfig();
            LlmProvider provider = providerFactory.createObserved(config, LlmCallContext.builder(workflowType(), REVISE_CANDIDATE)
                    .agentRunId(context.runId()).agentStepId(context.stepId()).logicalCallId("agent-step:" + context.stepId() + ":revision")
                    .promptTemplateVersion("generation-revision-v1").sourceFingerprint(evaluationService.sourceFingerprint(reportId)).build());
            LlmResponse response = provider.generate(new LlmRequest(List.of(
                    new LlmMessage(LlmRole.SYSTEM, "仅输出 JSON 对象 {\"revisionContent\":\"...\"}，只改写给定证据范围，不确认或修改故事事实。"),
                    new LlmMessage(LlmRole.USER, objectMapper.writeValueAsString(evaluationService.revisionInput(reportId, findings)))),
                    new LlmOptions(2048, null, List.of(), LlmResponseFormat.JSON_OBJECT)));
            JsonNode output = response == null ? null : response.structuredContent();
            if (output == null || !output.isObject() || output.size() != 1 || !output.has("revisionContent")
                    || !output.get("revisionContent").isTextual()) {
                throw new IllegalArgumentException("模型修订未返回合法结构化正文片段");
            }
            return output.get("revisionContent").textValue();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("正文局部修订 Provider 调用失败", exception);
        }
    }
}
