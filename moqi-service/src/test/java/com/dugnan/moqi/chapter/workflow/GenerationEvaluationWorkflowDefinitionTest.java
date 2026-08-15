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
        when(service.semanticSource(9L)).thenReturn("{\"scene\":\"draft\"}");
        EvaluationFinding finding = new EvaluationFinding("causality-1", "causality", "warning", 0.9D, "llm", 7L,
                "第1段", null, "因果衔接不足", "人工检查");
        when(service.validateSemanticFindings(org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(finding));
        when(provider.generate(any())).thenReturn(new LlmResponse(null,
                objectMapper.readTree("{\"findings\":[{\"issueKey\":\"causality-1\",\"category\":\"causality\","
                        + "\"severity\":\"warning\",\"confidence\":0.9,\"source\":\"llm\",\"generationSceneId\":7,"
                        + "\"evidenceRange\":\"第1段\",\"summary\":\"因果衔接不足\",\"suggestedAction\":\"人工检查\"}]}"), null));
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
}
