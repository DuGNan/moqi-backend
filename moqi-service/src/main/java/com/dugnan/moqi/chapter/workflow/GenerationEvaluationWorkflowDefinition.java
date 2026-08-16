package com.dugnan.moqi.chapter.workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationFinding;
import com.dugnan.moqi.chapter.service.EvaluationFindingContractException;
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

    private static final double MIN_CONFIDENCE = 0D;
    private static final double MAX_CONFIDENCE = 1D;
    private static final String ISSUE_KEY_FIELD = "issueKey";
    private static final String CATEGORY_FIELD = "category";
    private static final String SEVERITY_FIELD = "severity";
    private static final String CONFIDENCE_FIELD = "confidence";
    private static final String SOURCE_FIELD = "source";
    private static final String EVIDENCE_RANGE_FIELD = "evidenceRange";
    private static final String STORY_FACT_REF_FIELD = "storyFactRef";
    private static final String SUMMARY_FIELD = "summary";
    private static final String SUGGESTED_ACTION_FIELD = "suggestedAction";
    private static final String VIOLATED_SOURCE_FIELD = "violatedSource";
    private static final String IMPACT_SCOPE_FIELD = "impactScope";
    private static final String BLOCKS_ACCEPTANCE_FIELD = "blocksAcceptance";
    private static final String AUTO_REVISION_FIELD = "suitableForAutoRevision";
    private static final String SCENE_KEY_PATTERN = "scene-[a-zA-Z0-9-]+";
    private static final String INTEGER_PATTERN = "[0-9]+";
    private static final List<String> FINDING_FIELDS = List.of(ISSUE_KEY_FIELD, CATEGORY_FIELD, SEVERITY_FIELD,
            CONFIDENCE_FIELD, SOURCE_FIELD, "generationSceneId", EVIDENCE_RANGE_FIELD, STORY_FACT_REF_FIELD,
            SUMMARY_FIELD, SUGGESTED_ACTION_FIELD, VIOLATED_SOURCE_FIELD, IMPACT_SCOPE_FIELD,
            BLOCKS_ACCEPTANCE_FIELD, AUTO_REVISION_FIELD);
    private static final List<String> REQUIRED_TEXT_FIELDS = List.of(
            ISSUE_KEY_FIELD, CATEGORY_FIELD, SEVERITY_FIELD, SOURCE_FIELD, SUMMARY_FIELD, SUGGESTED_ACTION_FIELD);
    private static final List<String> OPTIONAL_TEXT_FIELDS = List.of(
            EVIDENCE_RANGE_FIELD, STORY_FACT_REF_FIELD, VIOLATED_SOURCE_FIELD, IMPACT_SCOPE_FIELD);
    private static final List<String> BOOLEAN_FIELDS = List.of(BLOCKS_ACCEPTANCE_FIELD, AUTO_REVISION_FIELD);
    private static final List<String> SEVERITIES = List.of("blocking", "warning", "info");
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
        return SEMANTIC_EVALUATE.equals(stepKey) || REVISE_CANDIDATE.equals(stepKey)
                || RE_EVALUATE.equals(stepKey) ? 2 : 1;
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
            EvaluationBatch batch = evaluate(reportId, context);
            List<EvaluationFinding> semantic = batch.findings();
            List<EvaluationFinding> rules = objectMapper.convertValue(context.state().get("findings"), new TypeReference<>() { });
            List<EvaluationFinding> merged = new java.util.ArrayList<>(rules == null ? List.of() : rules);
            merged.addAll(semantic);
            return AgentStepResult.completed(evaluationOutput("semanticFindingCount", semantic.size(), batch.normalizedFieldPaths()),
                    Map.of("reportId", reportId, "findings", List.copyOf(merged)),
                    evaluationService.shouldRevise(reportId, merged) ? REVISE_CANDIDATE : FINALIZE);
        }
        if (REVISE_CANDIDATE.equals(stepKey)) {
            List<EvaluationFinding> findings = objectMapper.convertValue(
                    context.state().get("findings"), new TypeReference<>() { });
            evaluationService.persistRevision(reportId, findings, revise(reportId, findings, context));
            return AgentStepResult.completed(Map.of("revisionCreated", true), context.state(), RE_EVALUATE);
        }
        if (RE_EVALUATE.equals(stepKey)) {
            EvaluationBatch batch = evaluateSource(
                    reportId, context, evaluationService.revisedSemanticSource(reportId));
            return AgentStepResult.completed(evaluationOutput("reEvaluationFindingCount", batch.findings().size(),
                            batch.normalizedFieldPaths()),
                    Map.of("reportId", reportId, "findings", batch.findings()), FINALIZE);
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
        EvaluationOutputException outputException = findOutputException(exception);
        if (outputException != null) {
            evaluationService.fail(reportId(context), "evaluation_output_" + outputException.category(),
                    "评价输出字段 " + outputException.path() + " 不符合安全契约，候选保持不可采纳");
            return;
        }
        EvaluationFindingContractException findingException = findFindingException(exception);
        if (findingException != null) {
            evaluationService.fail(reportId(context), "evaluation_output_" + findingException.category(),
                    "评价输出字段 " + findingException.path() + " 不符合安全契约，候选保持不可采纳");
            return;
        }
        evaluationService.fail(reportId(context), "evaluation_failed",
                "评价步骤 " + stepKey + " 失败或超时，候选保持不可采纳");
    }

    private Long reportId(AgentStepExecutionContext context) {
        Object value = context.input().get("reportId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Agent Run 缺少 reportId");
    }

    private EvaluationBatch evaluate(Long reportId, AgentStepExecutionContext context) {
        return evaluateSource(reportId, context, evaluationService.semanticSource(reportId));
    }

    private EvaluationBatch evaluateSource(Long reportId, AgentStepExecutionContext context, String source) {
        try {
            LlmExecutionConfig config = userConfigService.requireAvailableExecutionConfig();
            LlmProvider provider = providerFactory.createObserved(config, LlmCallContext.builder(workflowType(), SEMANTIC_EVALUATE)
                    .workId(context.input().get("workId") instanceof Number number ? number.longValue() : null)
                    .aiTaskId(context.input().get("aiTaskId") instanceof Number number ? number.longValue() : null)
                    .agentRunId(context.runId()).agentStepId(context.stepId()).logicalCallId("agent-step:" + context.stepId() + ":evaluation")
                    .promptTemplateVersion(GenerationEvaluationServiceImpl.EVALUATOR_VERSION)
                    .sourceFingerprint(evaluationService.sourceFingerprint(reportId)).build());
            LlmResponse response = provider.generate(new LlmRequest(List.of(
                    new LlmMessage(LlmRole.SYSTEM, """
                            你是只读的整章质量评价器，不得修改、续写或确认正文，也不得输出隐藏推理。
                            仅输出 JSON 对象 {"findings":[]}。每条 finding 必须包含 issueKey、category、severity、
                            confidence、source、generationSceneId、evidenceRange、storyFactRef、summary、suggestedAction、
                            violatedSource、impactScope、blocksAcceptance、suitableForAutoRevision。
                            confidence 必须是 0.0 到 1.0 之间的 JSON 数字，例如 0.35；禁止输出 low、medium、high 等文本。
                            evidenceRange 必须是 JSON 字符串或 null，禁止输出 Object 或 Array。
                            generationSceneId 只能填写输入 sourceSnapshot.sceneId 对应的 JSON 整数；输入 sceneId 为 null 时必须输出 null，
                            禁止填写 scene-1 等 scene key。blocksAcceptance 和 suitableForAutoRevision 必须是 JSON 布尔值。
                            severity 只能是 blocking、warning、info；可空字段也必须显式输出并使用 null，不得省略字段。
                            逻辑、事实、连续性、视角越权、必需事件遗漏可阻塞；低置信或审美意见只能是 warning，
                            来源冲突、需要修改规划或权威设定的问题必须 blocksAcceptance=true 且 suitableForAutoRevision=false。
                            重点检查开场承接、重复事件、完整因果、人物主动性、时空路线、伤势/道具/设备状态、
                            信息获得顺序、敌方行动合理性、专名首次介绍、伪技术解释、事件压缩、描写/对话有效性和结尾兑现。
                            """),
                    new LlmMessage(LlmRole.USER, source)),
                    new LlmOptions(2048, null, List.of(), LlmResponseFormat.JSON_OBJECT)));
            evaluationService.recordModelCall(reportId,
                    response == null || response.metadata() == null ? null : response.metadata().modelCallId());
            JsonNode content = response == null ? null : response.structuredContent();
            EvaluationBatch batch = parseEvaluation(content, source);
            return new EvaluationBatch(evaluationService.validateSemanticFindings(reportId, batch.findings()),
                    batch.normalizedFieldPaths());
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("正文语义评价 Provider 调用失败", exception);
        }
    }

    private EvaluationBatch parseEvaluation(JsonNode content, String source) {
        if (content == null || !content.isObject() || content.size() != 1) {
            throw new EvaluationOutputException("invalid_structure", "$");
        }
        JsonNode findingNodes = content.get("findings");
        if (findingNodes == null) {
            throw new EvaluationOutputException("missing_field", "findings");
        }
        if (!findingNodes.isArray()) {
            throw new EvaluationOutputException("type_mismatch", "findings");
        }
        Long sourceSceneId = sourceSceneId(source);
        Set<String> allowedStoryFactRefs = sourceIds(source);
        List<EvaluationFinding> findings = new ArrayList<>();
        List<String> normalizedPaths = new ArrayList<>();
        for (int index = 0; index < findingNodes.size(); index++) {
            JsonNode value = findingNodes.get(index);
            String base = "findings[" + index + "]";
            if (!value.isObject()) {
                throw new EvaluationOutputException("type_mismatch", base);
            }
            ObjectNode finding = ((ObjectNode) value).deepCopy();
            requireFields(finding, base);
            normalizeSceneId(finding, base, sourceSceneId, normalizedPaths);
            normalizeEvidenceRange(finding, base, normalizedPaths);
            normalizeStoryFactRef(finding, base, allowedStoryFactRefs, normalizedPaths);
            normalizeAutoRevisionFlag(finding, base, normalizedPaths);
            validateFindingTypes(finding, base);
            try {
                findings.add(objectMapper.treeToValue(finding, EvaluationFinding.class));
            } catch (Exception exception) {
                throw new EvaluationOutputException("type_mismatch", base);
            }
        }
        return new EvaluationBatch(List.copyOf(findings), List.copyOf(normalizedPaths));
    }

    private void requireFields(ObjectNode finding, String base) {
        for (String field : FINDING_FIELDS) {
            if (!finding.has(field)) {
                throw new EvaluationOutputException("missing_field", base + "." + field);
            }
        }
    }

    private void normalizeSceneId(ObjectNode finding, String base, Long sourceSceneId, List<String> normalizedPaths) {
        JsonNode sceneId = finding.get("generationSceneId");
        if (sceneId == null || sceneId.isNull()) {
            return;
        }
        String path = base + ".generationSceneId";
        if (sceneId.isIntegralNumber()) {
            if (sourceSceneId == null) {
                finding.putNull("generationSceneId");
                normalizedPaths.add(path);
                return;
            }
            if (sceneId.longValue() == sourceSceneId) {
                return;
            }
            throw new EvaluationOutputException("invalid_reference", path);
        }
        if (!sceneId.isTextual()) {
            throw new EvaluationOutputException("type_mismatch", path);
        }
        String text = sceneId.textValue().trim();
        if (sourceSceneId == null && text.matches(SCENE_KEY_PATTERN)) {
            finding.putNull("generationSceneId");
            normalizedPaths.add(path);
            return;
        }
        if (sourceSceneId != null && text.matches(INTEGER_PATTERN)) {
            try {
                long parsed = Long.parseLong(text);
                if (parsed == sourceSceneId) {
                    finding.put("generationSceneId", parsed);
                    normalizedPaths.add(path);
                    return;
                }
            } catch (NumberFormatException ignored) {
                // 超出 Long 范围仍按安全契约拒绝，不回退或猜测。
            }
        }
        throw new EvaluationOutputException("type_mismatch", path);
    }

    private void normalizeEvidenceRange(ObjectNode finding, String base, List<String> normalizedPaths) {
        JsonNode evidence = finding.get("evidenceRange");
        if (evidence == null || evidence.isNull() || evidence.isTextual()) {
            return;
        }
        String path = base + ".evidenceRange";
        if (!evidence.isObject()) {
            throw new EvaluationOutputException("type_mismatch", path);
        }
        Set<String> allowed = Set.of("text", "startOffset", "endOffset");
        Iterator<String> names = evidence.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw new EvaluationOutputException("unsafe_normalization", path);
            }
        }
        JsonNode text = evidence.get("text");
        if (text == null || !text.isTextual() || text.textValue().isBlank()) {
            throw new EvaluationOutputException("unsafe_normalization", path);
        }
        validateOptionalOffset(evidence, "startOffset", path);
        validateOptionalOffset(evidence, "endOffset", path);
        JsonNode start = evidence.get("startOffset");
        JsonNode end = evidence.get("endOffset");
        if (start != null && end != null && start.longValue() > end.longValue()) {
            throw new EvaluationOutputException("unsafe_normalization", path);
        }
        finding.put("evidenceRange", text.textValue());
        normalizedPaths.add(path);
    }

    private void validateOptionalOffset(JsonNode evidence, String field, String path) {
        JsonNode value = evidence.get(field);
        if (value == null) {
            return;
        }
        if (!value.isIntegralNumber() || value.longValue() < 0) {
            throw new EvaluationOutputException("unsafe_normalization", path);
        }
    }

    private void normalizeStoryFactRef(ObjectNode finding, String base, Set<String> allowedRefs,
            List<String> normalizedPaths) {
        JsonNode factRef = finding.get(STORY_FACT_REF_FIELD);
        if (factRef == null || factRef.isNull()) {
            return;
        }
        String path = base + "." + STORY_FACT_REF_FIELD;
        if (!factRef.isTextual()) {
            throw new EvaluationOutputException("type_mismatch", path);
        }
        if (!allowedRefs.contains(factRef.textValue())) {
            finding.putNull(STORY_FACT_REF_FIELD);
            normalizedPaths.add(path);
        }
    }

    private void normalizeAutoRevisionFlag(ObjectNode finding, String base, List<String> normalizedPaths) {
        JsonNode blocksAcceptance = finding.get(BLOCKS_ACCEPTANCE_FIELD);
        JsonNode autoRevision = finding.get(AUTO_REVISION_FIELD);
        if (blocksAcceptance != null && blocksAcceptance.isBoolean() && !blocksAcceptance.booleanValue()
                && autoRevision != null && autoRevision.isBoolean() && autoRevision.booleanValue()) {
            finding.put(AUTO_REVISION_FIELD, false);
            normalizedPaths.add(base + "." + AUTO_REVISION_FIELD);
        }
    }

    private void validateFindingTypes(ObjectNode finding, String base) {
        for (String field : REQUIRED_TEXT_FIELDS) {
            JsonNode value = finding.get(field);
            if (!value.isTextual()) {
                throw new EvaluationOutputException("type_mismatch", base + "." + field);
            }
            if (value.textValue().isBlank()) {
                throw new EvaluationOutputException("invalid_value", base + "." + field);
            }
        }
        JsonNode severity = finding.get(SEVERITY_FIELD);
        if (!SEVERITIES.contains(severity.textValue())) {
            throw new EvaluationOutputException("invalid_enum", base + "." + SEVERITY_FIELD);
        }
        JsonNode confidence = finding.get(CONFIDENCE_FIELD);
        if (!confidence.isNumber()) {
            throw new EvaluationOutputException("type_mismatch", base + "." + CONFIDENCE_FIELD);
        }
        if (!Double.isFinite(confidence.doubleValue())
                || confidence.doubleValue() < MIN_CONFIDENCE || confidence.doubleValue() > MAX_CONFIDENCE) {
            throw new EvaluationOutputException("invalid_value", base + "." + CONFIDENCE_FIELD);
        }
        for (String field : OPTIONAL_TEXT_FIELDS) {
            JsonNode value = finding.get(field);
            if (!value.isNull() && !value.isTextual()) {
                throw new EvaluationOutputException("type_mismatch", base + "." + field);
            }
        }
        for (String field : BOOLEAN_FIELDS) {
            if (!finding.get(field).isBoolean()) {
                throw new EvaluationOutputException("type_mismatch", base + "." + field);
            }
        }
    }

    private Long sourceSceneId(String source) {
        try {
            JsonNode sourceNode = objectMapper.readTree(source);
            JsonNode sceneId = sourceNode == null ? null : sourceNode.get("sceneId");
            if (sceneId == null || sceneId.isNull()) {
                return null;
            }
            if (!sceneId.isIntegralNumber()) {
                throw new IllegalStateException("评价来源 sceneId 不是整数");
            }
            return sceneId.longValue();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("评价来源快照无法读取", exception);
        }
    }

    private Set<String> sourceIds(String source) {
        try {
            Set<String> result = new java.util.LinkedHashSet<>();
            collectSourceIds(objectMapper.readTree(source), result);
            return Set.copyOf(result);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("评价来源快照无法读取", exception);
        }
    }

    private void collectSourceIds(JsonNode node, Set<String> result) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode sourceId = node.get("sourceId");
            if (sourceId != null && sourceId.isValueNode()) {
                result.add(sourceId.asText());
            }
            node.elements().forEachRemaining(value -> collectSourceIds(value, result));
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(value -> collectSourceIds(value, result));
        }
    }

    private Map<String, Object> evaluationOutput(String countKey, int count, List<String> normalizedPaths) {
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put(countKey, count);
        output.put("normalizedFieldCount", normalizedPaths.size());
        output.put("normalizedFieldPaths", normalizedPaths);
        return Map.copyOf(output);
    }

    private EvaluationOutputException findOutputException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof EvaluationOutputException outputException) {
                return outputException;
            }
            current = current.getCause();
        }
        return null;
    }

    private EvaluationFindingContractException findFindingException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof EvaluationFindingContractException findingException) {
                return findingException;
            }
            current = current.getCause();
        }
        return null;
    }

    private record EvaluationBatch(List<EvaluationFinding> findings, List<String> normalizedFieldPaths) {
    }

    static final class EvaluationOutputException extends IllegalArgumentException {
        private final String category;
        private final String path;

        EvaluationOutputException(String category, String path) {
            super(category + " at " + path);
            this.category = category;
            this.path = path;
        }

        String category() {
            return category;
        }

        String path() {
            return path;
        }
    }

    private String revise(Long reportId, List<EvaluationFinding> findings, AgentStepExecutionContext context) {
        try {
            LlmExecutionConfig config = userConfigService.requireAvailableExecutionConfig();
            LlmProvider provider = providerFactory.createObserved(config,
                    LlmCallContext.builder(workflowType(), REVISE_CANDIDATE)
                            .agentRunId(context.runId()).agentStepId(context.stepId())
                            .logicalCallId("agent-step:" + context.stepId() + ":revision")
                            .promptTemplateVersion("generation-revision-v1")
                            .sourceFingerprint(evaluationService.sourceFingerprint(reportId)).build());
            LlmResponse response = provider.generate(new LlmRequest(List.of(
                    new LlmMessage(LlmRole.SYSTEM,
                            "仅输出 JSON 对象 {\"revisionContent\":\"...\"}，只改写给定证据范围，不确认或修改故事事实。"),
                    new LlmMessage(LlmRole.USER,
                            objectMapper.writeValueAsString(evaluationService.revisionInput(reportId, findings)))),
                    new LlmOptions(2048, null, List.of(), LlmResponseFormat.JSON_OBJECT)));
            JsonNode output = response == null ? null : response.structuredContent();
            if (output == null || !output.isObject() || output.size() != 1
                    || !output.has("revisionContent") || !output.get("revisionContent").isTextual()) {
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
