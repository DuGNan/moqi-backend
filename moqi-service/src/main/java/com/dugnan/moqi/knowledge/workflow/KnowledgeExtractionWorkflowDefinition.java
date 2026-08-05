package com.dugnan.moqi.knowledge.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ExtractionOutput;
import com.dugnan.moqi.knowledge.service.impl.KnowledgeExtractionServiceImpl;
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
 * @description 通过 Agent Runtime 执行可恢复的已采纳正文知识提取与候选持久化。
 */
@Component
public class KnowledgeExtractionWorkflowDefinition implements AgentWorkflowDefinition {

    private static final String PRECHECK = "precheck";
    private static final String EXTRACT = "extract";
    private static final String VALIDATE = "validate";
    private static final String PERSIST = "persist";
    private static final int MAX_OUTPUT_TOKENS = 4096;

    private final KnowledgeExtractionServiceImpl extractionService;
    private final LlmProviderFactory providerFactory;
    private final UserConfigService userConfigService;
    private final ObjectMapper objectMapper;

    public KnowledgeExtractionWorkflowDefinition(
            KnowledgeExtractionServiceImpl extractionService,
            LlmProviderFactory providerFactory,
            UserConfigService userConfigService,
            ObjectMapper objectMapper) {
        this.extractionService = extractionService;
        this.providerFactory = providerFactory;
        this.userConfigService = userConfigService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String workflowType() {
        return KnowledgeExtractionServiceImpl.WORKFLOW_TYPE;
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
        return EXTRACT.equals(stepKey) ? 3 : 1;
    }

    @Override
    public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) {
        Long batchId = batchId(context);
        if (PRECHECK.equals(stepKey)) {
            extractionService.markRunning(batchId);
            extractionService.sourceContent(batchId);
            return AgentStepResult.completed(
                    Map.of("batchId", batchId),
                    Map.of("batchId", batchId),
                    EXTRACT);
        }
        if (EXTRACT.equals(stepKey)) {
            ExtractionOutput output = extract(batchId, context);
            return AgentStepResult.completed(
                    Map.of("candidateCount", output.candidates().size()),
                    Map.of("batchId", batchId, "output", output),
                    VALIDATE);
        }
        if (VALIDATE.equals(stepKey)) {
            ExtractionOutput output =
                    objectMapper.convertValue(context.state().get("output"), ExtractionOutput.class);
            ExtractionOutput validated = extractionService.validateOutput(batchId, output);
            return AgentStepResult.completed(
                    Map.of("candidateCount", validated.candidates().size()),
                    Map.of("batchId", batchId, "output", validated),
                    PERSIST);
        }
        if (PERSIST.equals(stepKey)) {
            ExtractionOutput output =
                    objectMapper.convertValue(context.state().get("output"), ExtractionOutput.class);
            extractionService.persist(batchId, output);
            return AgentStepResult.completed(
                    Map.of("batchId", batchId, "persisted", true),
                    Map.of("batchId", batchId),
                    null);
        }
        throw new IllegalArgumentException("未知故事知识提取步骤");
    }

    @Override
    public void applyFailure(
            String stepKey,
            AgentStepExecutionContext context,
            Exception exception) {
        extractionService.fail(batchId(context), "KNOWLEDGE_EXTRACTION_" + stepKey.toUpperCase() + "_FAILED");
    }

    private ExtractionOutput extract(Long batchId, AgentStepExecutionContext context) {
        try {
            LlmExecutionConfig config = userConfigService.requireAvailableExecutionConfig();
            LlmProvider provider = providerFactory.createObserved(
                    config,
                    LlmCallContext.builder(workflowType(), EXTRACT)
                            .workId(number(context.input().get("workId")))
                            .chapterId(number(context.input().get("chapterId")))
                            .aiTaskId(number(context.input().get("aiTaskId")))
                            .agentRunId(context.runId())
                            .agentStepId(context.stepId())
                            .logicalCallId("agent-step:" + context.stepId() + ":knowledge-extraction")
                            .promptTemplateVersion(KnowledgeExtractionServiceImpl.EXTRACTOR_VERSION)
                            .sourceFingerprint(extractionService.sourceFingerprint(batchId))
                            .build());
            LlmResponse response = provider.generate(new LlmRequest(
                    List.of(
                            new LlmMessage(
                                    LlmRole.SYSTEM,
                                    "仅输出 schemaVersion=1 的 JSON 对象。candidates 只能包含 "
                                            + "chapter_summary、key_event、setting、foreshadowing；"
                                            + "每项必须有 candidateKey、candidateType、payload、"
                                            + "evidence{startOffset,endOffset,text}。不要确认或修改权威事实。"),
                            new LlmMessage(LlmRole.USER, extractionService.sourceContent(batchId))),
                    new LlmOptions(
                            MAX_OUTPUT_TOKENS,
                            null,
                            List.of(),
                            LlmResponseFormat.JSON_OBJECT)));
            JsonNode content = response == null ? null : response.structuredContent();
            if (content == null || !content.isObject()
                    || !content.has("schemaVersion") || !content.has("candidates")
                    || !content.get("candidates").isArray()) {
                throw new IllegalArgumentException("模型未返回合法的故事知识提取结构");
            }
            return objectMapper.treeToValue(content, ExtractionOutput.class);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("故事知识提取 Provider 调用失败", exception);
        }
    }

    private Long batchId(AgentStepExecutionContext context) {
        Long value = number(context.input().get("batchId"));
        if (value == null) {
            throw new IllegalArgumentException("Agent Run 缺少 batchId");
        }
        return value;
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
