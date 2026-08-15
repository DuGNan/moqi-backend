package com.dugnan.moqi.chapter.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.service.impl.BoundedChapterRevisionServiceImpl;
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
 * @date 2026-08-15
 * @description 执行一次整章有界修订并强制为新候选启动独立重新评价。
 */
@Component
public class BoundedChapterRevisionWorkflowDefinition implements AgentWorkflowDefinition {
    private static final String PRECHECK = "precheck";
    private static final String REVISE = "revise";
    private static final String PERSIST_CANDIDATE = "persist_candidate";
    private static final String START_RE_EVALUATION = "start_re_evaluation";
    private static final String FINALIZE = "finalize";

    private final BoundedChapterRevisionServiceImpl revisionService;
    private final LlmProviderFactory providerFactory;
    private final UserConfigService userConfigService;
    private final ObjectMapper objectMapper;

    public BoundedChapterRevisionWorkflowDefinition(
            BoundedChapterRevisionServiceImpl revisionService,
            LlmProviderFactory providerFactory,
            UserConfigService userConfigService,
            ObjectMapper objectMapper) {
        this.revisionService = revisionService;
        this.providerFactory = providerFactory;
        this.userConfigService = userConfigService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String workflowType() {
        return BoundedChapterRevisionServiceImpl.WORKFLOW_TYPE;
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
        return REVISE.equals(stepKey) ? 2 : 1;
    }

    @Override
    public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) throws Exception {
        Long revisionId = revisionId(context);
        if (PRECHECK.equals(stepKey)) {
            return AgentStepResult.completed(Map.of("revisionId", revisionId),
                    Map.of("revisionId", revisionId), REVISE);
        }
        if (REVISE.equals(stepKey)) {
            LlmResponse response = revise(revisionId, context);
            JsonNode output = response == null ? null : response.structuredContent();
            if (output == null || !output.isObject() || output.size() != 1
                    || !output.has("revisionContent") || !output.get("revisionContent").isTextual()) {
                throw new IllegalArgumentException("模型修订未返回合法整章候选");
            }
            Long modelCallId = response.metadata() == null ? null : response.metadata().modelCallId();
            Map<String, Object> state = new java.util.LinkedHashMap<>(context.state());
            state.put("revisionContent", output.get("revisionContent").textValue());
            if (modelCallId != null) {
                state.put("modelCallId", modelCallId);
            }
            return AgentStepResult.completed(Map.of("revisionCreated", true), state, PERSIST_CANDIDATE);
        }
        if (PERSIST_CANDIDATE.equals(stepKey)) {
            return AgentStepResult.completed(Map.of("candidatePersisted", true), context.state(), START_RE_EVALUATION);
        }
        if (START_RE_EVALUATION.equals(stepKey)) {
            return AgentStepResult.completed(Map.of("reEvaluationStarted", true), context.state(), FINALIZE);
        }
        if (FINALIZE.equals(stepKey)) {
            return AgentStepResult.completed(Map.of("revisionId", revisionId), context.state(), null);
        }
        throw new IllegalArgumentException("未知整章有界修订步骤");
    }

    @Override
    public void applyResult(String stepKey, AgentStepExecutionContext context, AgentStepResult result) {
        Long revisionId = revisionId(context);
        if (PRECHECK.equals(stepKey)) {
            revisionService.markRunning(revisionId);
        } else if (PERSIST_CANDIDATE.equals(stepKey)) {
            Object modelCall = context.state().get("modelCallId");
            revisionService.persistCandidate(revisionId, String.valueOf(context.state().get("revisionContent")),
                    modelCall instanceof Number number ? number.longValue() : null);
        } else if (START_RE_EVALUATION.equals(stepKey)) {
            revisionService.startReEvaluation(revisionId);
        } else if (FINALIZE.equals(stepKey)) {
            revisionService.completeWorkflow(revisionId);
        }
    }

    @Override
    public void applyFailure(String stepKey, AgentStepExecutionContext context, Exception exception) {
        revisionService.fail(revisionId(context), stepKey);
    }

    private LlmResponse revise(Long revisionId, AgentStepExecutionContext context) throws Exception {
        LlmExecutionConfig config = userConfigService.requireAvailableExecutionConfig();
        LlmProvider provider = providerFactory.createObserved(config,
                LlmCallContext.builder(workflowType(), REVISE)
                        .agentRunId(context.runId()).agentStepId(context.stepId())
                        .logicalCallId("agent-step:" + context.stepId() + ":bounded-revision")
                        .promptTemplateVersion(BoundedChapterRevisionServiceImpl.TEMPLATE_VERSION)
                        .build());
        return provider.generate(new LlmRequest(List.of(
                new LlmMessage(LlmRole.SYSTEM, """
                        你是整章正文的有界修订器。仅输出 JSON 对象 {"revisionContent":"..."}。
                        必须同时处理任务书中彼此兼容的 finding，只修改有证据的问题；保留冻结 Brief、来源事实、
                        未命中证据范围的有效段落和原有叙事意图。不得新增或确认权威设定，不得修改规划，
                        不得声称采纳、发布或覆盖任何正文。输出完整修订正文，不输出解释和隐藏推理。
                        """),
                new LlmMessage(LlmRole.USER,
                        objectMapper.writeValueAsString(revisionService.workflowInput(revisionId)))),
                new LlmOptions(8192, null, List.of(), LlmResponseFormat.JSON_OBJECT)));
    }

    private Long revisionId(AgentStepExecutionContext context) {
        Object value = context.input().get("revisionId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Agent Run 缺少 revisionId");
    }
}
