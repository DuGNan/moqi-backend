package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import com.dugnan.moqi.llm.LlmResponse;

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
        when(service.validateSemanticFindings(9L, List.of(finding))).thenReturn(List.of(finding));
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
    }

    @Test
    void persistsOneStructuredRevisionCandidateWithFakeProvider() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GenerationEvaluationServiceImpl service = mock(GenerationEvaluationServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        EvaluationFinding finding = new EvaluationFinding("style-1", "style", "warning", 0.9D, "llm", 7L,
                "第1段", null, "表达重复", "局部改写");
        when(service.revisionInput(9L, List.of(finding))).thenReturn(Map.of("originalSceneContent", "原文", "evidenceRange", "第1段"));
        when(provider.generate(any())).thenReturn(new LlmResponse(null, objectMapper.readTree("{\"revisionContent\":\"修订片段\"}"), null));
        GenerationEvaluationWorkflowDefinition workflow = new GenerationEvaluationWorkflowDefinition(service, providerFactory,
                userConfigService, objectMapper);

        AgentStepResult result = workflow.execute("revise_candidate", new AgentStepExecutionContext(1L, 2L,
                "revise_candidate", 1, "effect", Map.of("reportId", 9L), Map.of("findings", List.of(finding)), Map.of(), null));

        assertThat(result.nextStepKey()).isEqualTo("re_evaluate");
        org.mockito.Mockito.verify(service).persistRevision(9L, List.of(finding), "修订片段");
    }

    @Test
    void rejectsInvalidStructuredRevisionOutput() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        GenerationEvaluationServiceImpl service = mock(GenerationEvaluationServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        EvaluationFinding finding = new EvaluationFinding("style-1", "style", "warning", 0.9D, "llm", 7L,
                "第1段", null, "表达重复", "局部改写");
        when(service.revisionInput(9L, List.of(finding))).thenReturn(Map.of("originalSceneContent", "原文", "evidenceRange", "第1段"));
        when(provider.generate(any())).thenReturn(new LlmResponse(null, objectMapper.readTree("{\"content\":\"错误字段\"}"), null));
        GenerationEvaluationWorkflowDefinition workflow = new GenerationEvaluationWorkflowDefinition(service, providerFactory,
                userConfigService, objectMapper);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> workflow.execute("revise_candidate",
                new AgentStepExecutionContext(1L, 2L, "revise_candidate", 1, "effect", Map.of("reportId", 9L),
                        Map.of("findings", List.of(finding)), Map.of(), null))).isInstanceOf(IllegalArgumentException.class);
    }
}
