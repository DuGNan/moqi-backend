package com.dugnan.moqi.impact;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.impact.ProseImpactModels.ImpactAnalysis;
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
 * @description 在 Agent Runtime 中执行正文 revision 的结构化事实影响分析。
 */
@Component
public class ProseImpactWorkflowDefinition implements AgentWorkflowDefinition {
    private static final String PREPARE = "prepare";
    private static final String FINALIZE = "finalize";
    private static final String MISSING_REPORT_MESSAGE = "Agent Run 缺少 reportId";
    private static final String INVALID_JSON_MESSAGE = "模型未返回 JSON 对象";
    private final ProseImpactServiceImpl service;
    private final LlmProviderFactory providerFactory;
    private final UserConfigService configService;
    private final ObjectMapper objectMapper;

    public ProseImpactWorkflowDefinition(@Lazy ProseImpactServiceImpl service, LlmProviderFactory providerFactory,
            UserConfigService configService, ObjectMapper objectMapper) {
        this.service = service; this.providerFactory = providerFactory; this.configService = configService;
        this.objectMapper = objectMapper;
    }
    @Override public String workflowType() { return ProseImpactServiceImpl.WORKFLOW_TYPE; }
    @Override public String startStepKey() { return PREPARE; }
    @Override public Duration timeout() { return Duration.ofMinutes(30); }
    @Override public int maxAttempts(String stepKey) { return ProseImpactServiceImpl.ANALYZE_STEP.equals(stepKey) ? 3 : 1; }

    @Override public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) {
        Long reportId = number(context.input().get("reportId"));
        if (reportId == null) { throw new IllegalArgumentException(MISSING_REPORT_MESSAGE); }
        if (PREPARE.equals(stepKey)) {
            service.markRunning(reportId);
            return AgentStepResult.completed(Map.of("reportId", reportId), Map.of("reportId", reportId),
                    ProseImpactServiceImpl.ANALYZE_STEP);
        }
        if (ProseImpactServiceImpl.ANALYZE_STEP.equals(stepKey)) {
            ModelResult result = analyze(reportId, context);
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("reportId", reportId); state.put("analysis", result.analysis());
            state.put("modelCallId", result.modelCallId());
            return AgentStepResult.completed(Map.of("impactScope", result.analysis().impactScope()), state, FINALIZE);
        }
        if (FINALIZE.equals(stepKey)) {
            ImpactAnalysis analysis = objectMapper.convertValue(context.state().get("analysis"), ImpactAnalysis.class);
            service.complete(reportId, analysis, number(context.state().get("modelCallId")));
            return AgentStepResult.completed(Map.of("reportId", reportId), context.state(), null);
        }
        throw new IllegalArgumentException("未知正文影响分析步骤");
    }

    @Override public void applyFailure(String stepKey, AgentStepExecutionContext context, Exception exception) {
        Long reportId = number(context.input().get("reportId"));
        if (reportId != null) { service.fail(reportId, exception); }
    }

    private ModelResult analyze(Long reportId, AgentStepExecutionContext context) {
        try {
            LlmExecutionConfig config = configService.requireAvailableExecutionConfig();
            LlmProvider provider = providerFactory.createObserved(config,
                    LlmCallContext.builder(workflowType(), ProseImpactServiceImpl.ANALYZE_STEP)
                            .workId(number(context.input().get("workId")))
                            .agentRunId(context.runId()).agentStepId(context.stepId())
                            .logicalCallId("agent-step:" + context.stepId() + ":prose-impact")
                            .promptTemplateVersion(ProseImpactServiceImpl.ANALYZER_VERSION).build());
            LlmResponse response = provider.generate(new LlmRequest(List.of(
                    new LlmMessage(LlmRole.SYSTEM, instruction()),
                    new LlmMessage(LlmRole.USER, service.analysisSource(reportId))),
                    new LlmOptions(4096, null, List.of(), LlmResponseFormat.JSON_OBJECT)));
            JsonNode output = response == null ? null : response.structuredContent();
            if (output == null || !output.isObject()) { throw new IllegalArgumentException(INVALID_JSON_MESSAGE); }
            ImpactAnalysis analysis = service.validateForReport(
                    reportId, objectMapper.treeToValue(output, ImpactAnalysis.class));
            Long modelCallId = response.metadata() == null ? null : response.metadata().modelCallId();
            return new ModelResult(analysis, modelCallId);
        } catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("影响分析模型输出无效", exception); }
    }

    private String instruction() {
        return "仅输出一个完整 ImpactAnalysis JSON 对象，禁止省略任何下述必填字段。顶层必须同时包含"
                + " impactScope、summary、changes。每个 changes 元素必须同时包含 changeKey、factType、"
                + "epistemicStatus、changeKind、impactScope、evidenceText、evidenceStartOffset、"
                + "evidenceEndOffset、confidence、directDependency、explanation、affectedChapterIds。"
                + "affectedChapterIds 必须只列出本事实实际影响的同作品章节 ID；cross_chapter 不能用全书"
                + "章节代替真实引用范围。impactScope 只能为"
                + " none、language_only、local、adjacent、"
                + "cross_chapter、work、unknown。changes 中 factType 只能为 event、character_state、"
                + "object_resource、space_time_route、causality、faction_rule、foreshadowing、language_only；"
                + "epistemicStatus 必须区分 objective、character_claim、rumor、speculation、unexplained、"
                + "author_backstage；changeKind 只能为 added、removed、modified、reframed；confidence 必须"
                + "为 0 到 1 的数字，directDependency 必须为布尔值。每条 evidenceText 和 UTF-16 offset"
                + " 必须精确指向 target 正文。没有事实变化时 changes 必须为空数组，范围只能为 none 或"
                + " language_only。完整格式示例：{\"impactScope\":\"local\",\"summary\":\"地点变化\","
                + "\"changes\":[{\"changeKey\":\"fact-1\",\"factType\":\"space_time_route\","
                + "\"epistemicStatus\":\"objective\",\"changeKind\":\"modified\",\"impactScope\":"
                + "\"local\",\"evidenceText\":\"目标正文中的原文\",\"evidenceStartOffset\":0,"
                + "\"evidenceEndOffset\":9,\"confidence\":0.9,\"directDependency\":true,"
                + "\"explanation\":\"变化解释\",\"affectedChapterIds\":[1]}]}。"
                + "角色主张、传闻与推测不是权威事实。报告只是候选分析，不得确认知识或修改正文。";
    }
    private Long number(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private record ModelResult(ImpactAnalysis analysis, Long modelCallId) { }
}
