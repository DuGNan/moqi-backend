package com.dugnan.moqi.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.dugnan.moqi.agent.AgentRunCallRegistry;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmExecutionConfigDescriptor;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderError;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.impact.ProseImpactModels.ImpactAnalysis;

@ExtendWith(MockitoExtension.class)
class ProseImpactWorkflowDefinitionTest {
    @Mock private ProseImpactServiceImpl service;
    @Mock private LlmProviderFactory providerFactory;
    @Mock private UserConfigService configService;
    @Mock private LlmProvider fakeProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProseImpactWorkflowDefinition workflow;

    @BeforeEach
    void setUp() {
        workflow = new ProseImpactWorkflowDefinition(service, providerFactory, configService, objectMapper);
        when(configService.requireAvailableExecutionConfig()).thenReturn(new LlmExecutionConfig(
                new LlmProviderRuntimeConfig("fake", "http://fake", "test-only", "fake-model"),
                new LlmExecutionConfigDescriptor("fake", "fake-model", 1, 1)));
        when(providerFactory.createObserved(any(), any())).thenReturn(fakeProvider);
        when(service.analysisSource(20L)).thenReturn("{\"baseline\":\"旧\",\"target\":\"林舟抵达北城\"}");
        lenient().when(service.validateForReport(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void fakeProviderProducesRecoverableStructuredCheckpoint() throws Exception {
        var json = objectMapper.readTree("""
                {"impactScope":"local","summary":"地点变化","changes":[{"changeKey":"fact-1",
                "factType":"event","epistemicStatus":"objective","changeKind":"modified",
                "impactScope":"local","evidenceText":"抵达北城","evidenceStartOffset":2,
                "evidenceEndOffset":6,"confidence":0.91,"directDependency":true,"explanation":"地点变化",
                "affectedChapterIds":[2]}]}
                """);
        when(fakeProvider.generate(any())).thenReturn(new LlmResponse(null, json, null));

        var result = workflow.execute(ProseImpactServiceImpl.ANALYZE_STEP, context());

        assertThat(result.nextStepKey()).isEqualTo("finalize");
        assertThat(result.checkpointState()).containsKeys("reportId", "analysis", "modelCallId");
    }

    @Test
    void providerTimeoutFailsInsteadOfPublishingFallbackFacts() {
        when(fakeProvider.generate(any())).thenThrow(new LlmProviderException(LlmProviderError.TIMEOUT));

        assertThatThrownBy(() -> workflow.execute(ProseImpactServiceImpl.ANALYZE_STEP, context()))
                .isInstanceOf(LlmProviderException.class);
        workflow.applyFailure(ProseImpactServiceImpl.ANALYZE_STEP, context(),
                new LlmProviderException(LlmProviderError.TIMEOUT));
        verify(service).fail(any(), any());
    }

    @Test
    void invalidProviderJsonFailsAndCanBeRetriedByAgentRuntime() throws Exception {
        when(fakeProvider.generate(any())).thenReturn(new LlmResponse(null, objectMapper.readTree("[]"), null));
        assertThatThrownBy(() -> workflow.execute(ProseImpactServiceImpl.ANALYZE_STEP, context()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON");
        assertThat(workflow.maxAttempts(ProseImpactServiceImpl.ANALYZE_STEP)).isEqualTo(3);
    }

    @Test
    void deterministicValidationFailureHappensInRetryableAnalyzeStep() throws Exception {
        var incomplete = objectMapper.readTree("{\"impactScope\":\"local\",\"changes\":[]}");
        when(fakeProvider.generate(any())).thenReturn(new LlmResponse(null, incomplete, null));
        when(service.validateForReport(any(), any(ImpactAnalysis.class)))
                .thenThrow(new IllegalArgumentException("缺少摘要"));

        assertThatThrownBy(() -> workflow.execute(ProseImpactServiceImpl.ANALYZE_STEP, context()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("摘要");
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(51L, 61L, ProseImpactServiceImpl.ANALYZE_STEP, 1, "effect",
                Map.of("reportId", 20L, "workId", 1L, "chapterId", 2L), Map.of("reportId", 20L),
                Map.of(), new AgentRunCallRegistry());
    }
}
