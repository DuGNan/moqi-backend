package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationFinding;
import com.dugnan.moqi.chapter.service.impl.GenerationEvaluationServiceImpl;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import org.mockito.ArgumentCaptor;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 验证正文评价工作流使用结构化 Provider 输出而不写入正文。
 */
class GenerationEvaluationWorkflowDefinitionTest {

    @Test
    void evaluatesStructuredFindingsWithFakeProvider() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GenerationEvaluationServiceImpl service = mock(GenerationEvaluationServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        LlmExecutionConfig config = mock(LlmExecutionConfig.class);
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(config);
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.semanticSource(9L)).thenReturn("{\"sceneId\":7,\"scene\":\"draft\"}");
        EvaluationFinding finding = new EvaluationFinding("causality-1", "causality", "warning", 0.9D, "llm", 7L,
                "第1段", null, "因果衔接不足", "人工检查");
        when(service.validateSemanticFindings(org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(finding));
        when(provider.generate(any())).thenReturn(new LlmResponse(null,
                objectMapper.readTree("{\"findings\":[{\"issueKey\":\"causality-1\",\"category\":\"causality\","
                        + "\"severity\":\"warning\",\"confidence\":0.9,\"source\":\"llm\",\"generationSceneId\":7,"
                        + "\"evidenceRange\":\"第1段\",\"storyFactRef\":null,\"summary\":\"因果衔接不足\","
                        + "\"suggestedAction\":\"人工检查\",\"violatedSource\":null,\"impactScope\":null,"
                        + "\"blocksAcceptance\":false,\"suitableForAutoRevision\":false}]}"), null));
        GenerationEvaluationWorkflowDefinition workflow = new GenerationEvaluationWorkflowDefinition(service, providerFactory,
                userConfigService, objectMapper);

        AgentStepResult result = workflow.execute("semantic_evaluate", new AgentStepExecutionContext(1L, 2L,
                "semantic_evaluate", 1, "effect", Map.of("reportId", 9L), Map.of("findings", List.of()), Map.of(), null));

        assertThat(result.nextStepKey()).isEqualTo("finalize");
        assertThat((List<?>) result.checkpointState().get("findings")).hasSize(1);
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).generate(requestCaptor.capture());
        assertThat(requestCaptor.getValue().messages().get(0).content())
                .contains("0.0 到 1.0 之间的 JSON 数字")
                .contains("禁止输出 low、medium、high 等文本")
                .contains("evidenceRange 必须是 JSON 字符串或 null")
                .contains("只能填写输入 sourceSnapshot.sceneId")
                .contains("必须是 JSON 布尔值");
    }

    @Test
    void neverBranchesIntoAutomaticRevision() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GenerationEvaluationServiceImpl service = mock(GenerationEvaluationServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        EvaluationFinding finding = new EvaluationFinding("causality-1", "causality", "blocking", 0.95D,
                "llm", null, "第2段", null, "因果跳跃", "交给后续有界修订", "Brief 必需事件", "第2段",
                true, true);
        when(service.semanticSource(9L)).thenReturn("{\"chapter\":\"draft\"}");
        when(service.validateSemanticFindings(org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(finding));
        when(provider.generate(any())).thenReturn(new LlmResponse(null, objectMapper.valueToTree(Map.of("findings", List.of(finding))), null));
        GenerationEvaluationWorkflowDefinition workflow = new GenerationEvaluationWorkflowDefinition(service, providerFactory,
                userConfigService, objectMapper);

        AgentStepResult result = workflow.execute("semantic_evaluate", new AgentStepExecutionContext(1L, 2L,
                "semantic_evaluate", 1, "effect", Map.of("reportId", 9L), Map.of("findings", List.of()), Map.of(), null));

        assertThat(result.nextStepKey()).isEqualTo("finalize");
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .persistRevision(any(), any(), any());
    }

    @Test
    void rejectsInvalidStructuredEvaluationOutput() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GenerationEvaluationServiceImpl service = mock(GenerationEvaluationServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(provider.generate(any())).thenReturn(new LlmResponse(null, objectMapper.readTree("{\"content\":\"错误字段\"}"), null));
        when(service.semanticSource(9L)).thenReturn("{\"chapter\":\"draft\"}");
        GenerationEvaluationWorkflowDefinition workflow = new GenerationEvaluationWorkflowDefinition(service, providerFactory,
                userConfigService, objectMapper);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> workflow.execute("semantic_evaluate",
                new AgentStepExecutionContext(1L, 2L, "semantic_evaluate", 1, "effect", Map.of("reportId", 9L),
                        Map.of("findings", List.of()), Map.of(), null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesBoundedEquivalentEvidenceObjectAndWholeChapterSceneKey() throws Exception {
        Harness harness = harness("{\"sceneId\":null,\"generationContent\":\"正文\"}", """
                {"findings":[{"issueKey":"continuity-1","category":"continuity","severity":"blocking",
                "confidence":0.91,"source":"llm","generationSceneId":"scene-1",
                "evidenceRange":{"text":"第 3 段","startOffset":20,"endOffset":30},"storyFactRef":null,
                "summary":"状态不连续","suggestedAction":"人工核对","violatedSource":"冻结上下文",
                "impactScope":"第 3 段","blocksAcceptance":true,"suitableForAutoRevision":false}]}
                """);
        when(harness.service().validateSemanticFindings(org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(1));

        AgentStepResult result = harness.workflow().execute("semantic_evaluate", context());

        @SuppressWarnings("unchecked")
        List<EvaluationFinding> findings = (List<EvaluationFinding>) result.checkpointState().get("findings");
        assertThat(findings.get(0).generationSceneId()).isNull();
        assertThat(findings.get(0).evidenceRange()).isEqualTo("第 3 段");
        assertThat(result.outputSummary()).containsEntry("normalizedFieldCount", 2);
        assertThat((List<String>) result.outputSummary().get("normalizedFieldPaths"))
                .containsExactly("findings[0].generationSceneId", "findings[0].evidenceRange");
    }

    @Test
    void rejectsSceneKeyWhenEvaluationIsBoundToRealSceneId() throws Exception {
        Harness harness = harness("{\"sceneId\":7}", validFinding("\"scene-1\"", "\"第1段\"", "\"warning\""));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> harness.workflow().execute("semantic_evaluate", context()))
                .isInstanceOf(GenerationEvaluationWorkflowDefinition.EvaluationOutputException.class)
                .hasMessageContaining("findings[0].generationSceneId")
                .hasMessageContaining("type_mismatch");
    }

    @Test
    void removesUnverifiedFactReferenceAndDisablesUnsafeAutoRevision() throws Exception {
        Harness harness = harness("{\"sceneId\":null}", """
                {"findings":[{"issueKey":"style-1","category":"style","severity":"warning",
                "confidence":0.7,"source":"llm","generationSceneId":null,"evidenceRange":"第1段",
                "storyFactRef":"model-invented-ref","summary":"重复","suggestedAction":"人工检查",
                "violatedSource":null,"impactScope":null,"blocksAcceptance":false,
                "suitableForAutoRevision":true}]}
                """);
        when(harness.service().validateSemanticFindings(org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(1));

        AgentStepResult result = harness.workflow().execute("semantic_evaluate", context());

        @SuppressWarnings("unchecked")
        List<EvaluationFinding> findings = (List<EvaluationFinding>) result.checkpointState().get("findings");
        assertThat(findings.get(0).storyFactRef()).isNull();
        assertThat(findings.get(0).suitableForAutoRevision()).isFalse();
        assertThat((List<String>) result.outputSummary().get("normalizedFieldPaths"))
                .containsExactly("findings[0].storyFactRef", "findings[0].suitableForAutoRevision");
    }

    @Test
    void rejectsMissingFieldWithSafePathAndCategory() throws Exception {
        Harness harness = harness("{\"sceneId\":null}", """
                {"findings":[{"issueKey":"style-1","category":"style","severity":"warning",
                "confidence":0.7,"source":"llm","generationSceneId":null,"evidenceRange":"第1段",
                "storyFactRef":null,"summary":"重复","suggestedAction":"人工检查","violatedSource":null,
                "impactScope":null,"blocksAcceptance":false}]}
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> harness.workflow().execute("semantic_evaluate", context()))
                .isInstanceOf(GenerationEvaluationWorkflowDefinition.EvaluationOutputException.class)
                .hasMessageContaining("findings[0].suitableForAutoRevision")
                .hasMessageContaining("missing_field");
    }

    @Test
    void rejectsInvalidEnumWithoutAcceptingValidSiblingFinding() throws Exception {
        Harness harness = harness("{\"sceneId\":null}", """
                {"findings":[
                {"issueKey":"ok","category":"style","severity":"warning","confidence":0.7,"source":"llm",
                "generationSceneId":null,"evidenceRange":"第1段","storyFactRef":null,"summary":"重复",
                "suggestedAction":"人工检查","violatedSource":null,"impactScope":null,
                "blocksAcceptance":false,"suitableForAutoRevision":false},
                {"issueKey":"bad","category":"style","severity":"critical","confidence":0.9,"source":"llm",
                "generationSceneId":null,"evidenceRange":"第2段","storyFactRef":null,"summary":"错误枚举",
                "suggestedAction":"人工检查","violatedSource":null,"impactScope":null,
                "blocksAcceptance":false,"suitableForAutoRevision":false}]}
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> harness.workflow().execute("semantic_evaluate", context()))
                .isInstanceOf(GenerationEvaluationWorkflowDefinition.EvaluationOutputException.class)
                .hasMessageContaining("findings[1].severity")
                .hasMessageContaining("invalid_enum");
        verify(harness.service(), org.mockito.Mockito.never()).validateSemanticFindings(any(), any());
    }

    @Test
    void applyFailurePersistsSafeOutputClassification() {
        GenerationEvaluationServiceImpl service = mock(GenerationEvaluationServiceImpl.class);
        GenerationEvaluationWorkflowDefinition workflow = new GenerationEvaluationWorkflowDefinition(service,
                mock(LlmProviderFactory.class), mock(UserConfigService.class), new ObjectMapper());

        workflow.applyFailure("semantic_evaluate", context(),
                new GenerationEvaluationWorkflowDefinition.EvaluationOutputException(
                        "type_mismatch", "findings[0].evidenceRange"));

        verify(service).fail(9L, "evaluation_output_type_mismatch",
                "评价输出字段 findings[0].evidenceRange 不符合安全契约，候选保持不可采纳");
    }

    private Harness harness(String source, String output) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GenerationEvaluationServiceImpl service = mock(GenerationEvaluationServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.semanticSource(9L)).thenReturn(source);
        when(provider.generate(any())).thenReturn(new LlmResponse(null, objectMapper.readTree(output), null));
        return new Harness(new GenerationEvaluationWorkflowDefinition(service, providerFactory,
                userConfigService, objectMapper), service);
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(1L, 2L, "semantic_evaluate", 1, "effect",
                Map.of("reportId", 9L), Map.of("findings", List.of()), Map.of(), null);
    }

    private String validFinding(String sceneId, String evidenceRange, String severity) {
        return "{\"findings\":[{\"issueKey\":\"style-1\",\"category\":\"style\",\"severity\":" + severity
                + ",\"confidence\":0.7,\"source\":\"llm\",\"generationSceneId\":" + sceneId
                + ",\"evidenceRange\":" + evidenceRange + ",\"storyFactRef\":null,\"summary\":\"重复\","
                + "\"suggestedAction\":\"人工检查\",\"violatedSource\":null,\"impactScope\":null,"
                + "\"blocksAcceptance\":false,\"suitableForAutoRevision\":false}]}";
    }

    private record Harness(GenerationEvaluationWorkflowDefinition workflow, GenerationEvaluationServiceImpl service) {
    }
}
