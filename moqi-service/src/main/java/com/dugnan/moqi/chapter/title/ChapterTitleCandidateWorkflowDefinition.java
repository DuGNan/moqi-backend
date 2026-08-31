package com.dugnan.moqi.chapter.title;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
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
 * @date 2026-08-29
 * @description 在 Agent Runtime 中生成严格结构化、可恢复的章节标题候选。
 */
@Component
public class ChapterTitleCandidateWorkflowDefinition implements AgentWorkflowDefinition {

    private static final Set<String> ALLOWED_FIELDS = Set.of("titles");
    private static final int CANDIDATE_COUNT = 3;
    private final ChapterTitleCandidateServiceImpl service;
    private final LlmProviderFactory providerFactory;
    private final UserConfigService userConfigService;

    public ChapterTitleCandidateWorkflowDefinition(
            ChapterTitleCandidateServiceImpl service,
            LlmProviderFactory providerFactory,
            UserConfigService userConfigService) {
        this.service = service;
        this.providerFactory = providerFactory;
        this.userConfigService = userConfigService;
    }

    @Override
    public String workflowType() {
        return ChapterTitleCandidateServiceImpl.WORKFLOW_TYPE;
    }

    @Override
    public String startStepKey() {
        return ChapterTitleCandidateServiceImpl.GENERATE_STEP;
    }

    @Override
    public Duration timeout() {
        return Duration.ofMinutes(10);
    }

    @Override
    public int maxAttempts(String stepKey) {
        return ChapterTitleCandidateServiceImpl.GENERATE_STEP.equals(stepKey) ? 3 : 1;
    }

    @Override
    public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) throws Exception {
        if (!ChapterTitleCandidateServiceImpl.GENERATE_STEP.equals(stepKey)) {
            throw new IllegalArgumentException("未知的章节取名步骤");
        }
        Long batchId = number(context.input().get("batchId"));
        if (batchId == null) {
            throw new IllegalArgumentException("Agent Run 缺少 batchId");
        }
        if (!service.markRunning(batchId, context.attempt())) {
            throw new CancellationException("章节取名批次已取消");
        }
        LlmExecutionConfig config = userConfigService.requireAvailableExecutionConfig();
        String logicalCallId = "agent-step:" + context.stepId() + ":chapter-title-candidates";
        LlmProvider provider = providerFactory.createObserved(config,
                LlmCallContext.builder(workflowType(), stepKey)
                        .workId(number(context.input().get("workId")))
                        .aiTaskId(number(context.input().get("aiTaskId")))
                        .agentRunId(context.runId())
                        .agentStepId(context.stepId())
                        .logicalCallId(logicalCallId)
                        .promptTemplateVersion(ChapterTitleCandidateServiceImpl.PROMPT_TEMPLATE_VERSION)
                        .sourceFingerprint(service.sourceFingerprint(batchId))
                        .build());
        LlmResponse response = provider.generate(new LlmRequest(List.of(
                new LlmMessage(LlmRole.SYSTEM, systemPrompt()),
                new LlmMessage(LlmRole.USER, service.modelPrompt(batchId))),
                new LlmOptions(512, null, List.of(), LlmResponseFormat.JSON_OBJECT)));
        List<String> titles = parse(response == null ? null : response.structuredContent());
        service.complete(batchId, titles);
        return AgentStepResult.completed(Map.of("batchId", batchId, "candidateCount", titles.size()),
                Map.of("batchId", batchId), null);
    }

    @Override
    public void applyFailure(String stepKey, AgentStepExecutionContext context, Exception exception) {
        Long batchId = number(context.input().get("batchId"));
        if (batchId != null) {
            service.fail(batchId, errorCode(exception), exception.getMessage());
        }
    }

    @Override
    public String errorCategory(Exception exception) {
        return "provider";
    }

    @Override
    public String errorCode(Exception exception) {
        return exception instanceof IllegalArgumentException
                ? "CHAPTER_TITLE_INVALID_OUTPUT" : "CHAPTER_TITLE_PROVIDER_FAILED";
    }

    private List<String> parse(JsonNode output) {
        if (output == null || !output.isObject() || output.size() != 1
                || output.propertyStream().map(Map.Entry::getKey).anyMatch(field -> !ALLOWED_FIELDS.contains(field))) {
            throw new IllegalArgumentException("模型未返回唯一 titles 字段");
        }
        JsonNode titlesNode = output.get("titles");
        if (titlesNode == null || !titlesNode.isArray() || titlesNode.size() != CANDIDATE_COUNT) {
            throw new IllegalArgumentException("模型必须返回 3 个标题");
        }
        List<String> titles = new ArrayList<>();
        titlesNode.forEach(item -> {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("标题候选必须为字符串");
            }
            titles.add(item.textValue());
        });
        return List.copyOf(titles);
    }

    private String systemPrompt() {
        return "你只负责为已给定的中文小说章节提供标题候选。"
                + "仅输出 JSON 对象 {\"titles\":[\"...\",\"...\",\"...\"]}，且必须恰好 3 个不同标题。"
                + "不得输出解释、书名号、章序、‘标题：’或契约外字段。"
                + "标题候选只供作者选择，不得声称已采用或更新任何正文。";
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
