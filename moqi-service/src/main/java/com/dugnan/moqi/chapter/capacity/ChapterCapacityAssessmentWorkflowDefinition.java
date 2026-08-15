package com.dugnan.moqi.chapter.capacity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CapacityResult;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmCallContext;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmRole;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 在 Agent Runtime 中执行确定性预检、模型容量判断和候选结果收束。
 */
@Component
public class ChapterCapacityAssessmentWorkflowDefinition implements AgentWorkflowDefinition {

    private static final String PRECHECK = "precheck";
    private static final String FINALIZE = "finalize";
    private static final String TEMPLATE_VERSION = "chapter-capacity-evaluator-v1";
    private final ChapterCapacityAssessmentServiceImpl service;
    private final LlmProviderFactory providerFactory;
    private final UserConfigService userConfigService;
    private final ObjectMapper objectMapper;

    public ChapterCapacityAssessmentWorkflowDefinition(
            @Lazy ChapterCapacityAssessmentServiceImpl service,
            LlmProviderFactory providerFactory,
            UserConfigService userConfigService,
            ObjectMapper objectMapper) {
        this.service = service;
        this.providerFactory = providerFactory;
        this.userConfigService = userConfigService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String workflowType() {
        return ChapterCapacityAssessmentServiceImpl.WORKFLOW_TYPE;
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
        return ChapterCapacityAssessmentServiceImpl.SEMANTIC_STEP.equals(stepKey) ? 2 : 1;
    }

    @Override
    public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) {
        Long assessmentId = assessmentId(context);
        if (PRECHECK.equals(stepKey)) {
            service.markRunning(assessmentId);
            return AgentStepResult.completed(Map.of("assessmentId", assessmentId),
                    Map.of("assessmentId", assessmentId), ChapterCapacityAssessmentServiceImpl.SEMANTIC_STEP);
        }
        if (ChapterCapacityAssessmentServiceImpl.SEMANTIC_STEP.equals(stepKey)) {
            SemanticResult semanticResult = assess(assessmentId, context);
            Map<String, Object> state = new java.util.LinkedHashMap<>();
            state.put("assessmentId", assessmentId);
            state.put("result", semanticResult.result());
            state.put("modelCallId", semanticResult.modelCallId());
            return AgentStepResult.completed(Map.of("assessmentMode", semanticResult.result().assessmentMode()),
                    state, FINALIZE);
        }
        if (FINALIZE.equals(stepKey)) {
            CapacityResult result = objectMapper.convertValue(context.state().get("result"), CapacityResult.class);
            Long modelCallId = number(context.state().get("modelCallId"));
            service.complete(assessmentId, result, modelCallId);
            return AgentStepResult.completed(Map.of("assessmentId", assessmentId), context.state(), null);
        }
        throw new IllegalArgumentException("未知章节容量评估步骤");
    }

    @Override
    public void applyFailure(String stepKey, AgentStepExecutionContext context, Exception exception) {
        service.fail(assessmentId(context), exception);
    }

    private SemanticResult assess(Long assessmentId, AgentStepExecutionContext context) {
        try {
            LlmExecutionConfig config = userConfigService.requireAvailableExecutionConfig();
            LlmProvider provider = providerFactory.createObserved(config,
                    LlmCallContext.builder(workflowType(), ChapterCapacityAssessmentServiceImpl.SEMANTIC_STEP)
                            .workId(number(context.input().get("workId")))
                            .aiTaskId(number(context.input().get("aiTaskId")))
                            .agentRunId(context.runId()).agentStepId(context.stepId())
                            .logicalCallId("agent-step:" + context.stepId() + ":capacity")
                            .promptTemplateVersion(TEMPLATE_VERSION)
                            .sourceFingerprint(service.inputFingerprint(assessmentId)).build());
            if (service.requiresLongContext(assessmentId, provider.capabilities().maxContextTokens())) {
                return new SemanticResult(service.longContextFallback(assessmentId), null);
            }
            LlmResponse response = provider.generate(new LlmRequest(List.of(
                    new LlmMessage(LlmRole.SYSTEM, systemInstruction()),
                    new LlmMessage(LlmRole.USER, service.semanticSource(assessmentId))),
                    new LlmOptions(2048, null, List.of(), LlmResponseFormat.JSON_OBJECT)));
            JsonNode output = response == null ? null : response.structuredContent();
            if (output == null || !output.isObject()) {
                throw new IllegalArgumentException("模型未返回 JSON 对象");
            }
            CapacityResult candidate = objectMapper.treeToValue(output, CapacityResult.class);
            CapacityResult validated = service.validateSemantic(
                    assessmentId, candidate, provider.capabilities().maxContextTokens());
            Long modelCallId = response.metadata() == null ? null : response.metadata().modelCallId();
            return new SemanticResult(validated, modelCallId);
        } catch (LlmProviderException exception) {
            return new SemanticResult(service.fallback(assessmentId, "provider_failed"), null);
        } catch (IllegalArgumentException exception) {
            return new SemanticResult(service.fallback(assessmentId, "invalid_model_output"), null);
        } catch (Exception exception) {
            return new SemanticResult(service.fallback(assessmentId, "provider_failed"), null);
        }
    }

    private String systemInstruction() {
        return "仅输出符合 CapacityResult 的 JSON 对象。status 只能是 fits、too_dense、too_thin、"
                + "requires_long_context；eventWeights 只能引用输入中已有的 sceneKey。"
                + "评估结果只是供作者决策的候选，不得修改、确认或拆分任何规划。"
                + "assessmentMode、degradedReason 和 longContextRequired 可省略，由服务端校验并重写。";
    }

    private Long assessmentId(AgentStepExecutionContext context) {
        Long value = number(context.input().get("assessmentId"));
        if (value == null) {
            throw new IllegalArgumentException("Agent Run 缺少 assessmentId");
        }
        return value;
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private record SemanticResult(CapacityResult result, Long modelCallId) {
    }
}
