package com.dugnan.moqi.chapter.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.agent.AgentRunCallRegistry;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CapacityResult;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmExecutionConfigDescriptor;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderCapabilities;
import com.dugnan.moqi.llm.LlmProviderError;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证容量语义评估的 Provider 降级与长上下文证据路径。
 */
@ExtendWith(MockitoExtension.class)
class ChapterCapacityAssessmentWorkflowDefinitionTest {

    @Mock
    private ChapterCapacityAssessmentServiceImpl service;
    @Mock
    private LlmProviderFactory providerFactory;
    @Mock
    private UserConfigService userConfigService;
    @Mock
    private LlmProvider provider;
    private ChapterCapacityAssessmentWorkflowDefinition workflow;

    @BeforeEach
    void setUp() {
        workflow = new ChapterCapacityAssessmentWorkflowDefinition(
                service, providerFactory, userConfigService, new ObjectMapper());
        LlmExecutionConfig config = new LlmExecutionConfig(
                new LlmProviderRuntimeConfig("fake", "http://fake", "secret", "model"),
                new LlmExecutionConfigDescriptor("fake", "model", 1, 1));
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(config);
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.inputFingerprint(8L)).thenReturn("input-hash");
    }

    @Test
    void degradesProviderFailureToADeterministicReadyCandidate() {
        CapacityResult fallback = result("too_dense", "provider_failed");
        when(provider.capabilities()).thenReturn(new LlmProviderCapabilities(false, true, false, 32000, 4096));
        when(service.requiresLongContext(8L, 32000)).thenReturn(false);
        when(service.semanticSource(8L)).thenReturn("{}");
        when(provider.generate(any())).thenThrow(new LlmProviderException(LlmProviderError.SERVICE_UNAVAILABLE));
        when(service.fallback(8L, "provider_failed")).thenReturn(fallback);

        var result = workflow.execute(ChapterCapacityAssessmentServiceImpl.SEMANTIC_STEP, context());

        CapacityResult saved = new ObjectMapper().convertValue(
                result.checkpointState().get("result"), CapacityResult.class);
        assertThat(saved.assessmentMode()).isEqualTo("fallback");
        assertThat(saved.degradedReason()).isEqualTo("provider_failed");
        assertThat(result.nextStepKey()).isEqualTo("finalize");
    }

    @Test
    void recordsRequiresLongContextBeforeCallingTheProvider() {
        CapacityResult longContext = result("requires_long_context", "provider_context_insufficient");
        when(provider.capabilities()).thenReturn(new LlmProviderCapabilities(false, true, false, 1000, 4096));
        when(service.requiresLongContext(8L, 1000)).thenReturn(true);
        when(service.longContextFallback(8L)).thenReturn(longContext);

        var result = workflow.execute(ChapterCapacityAssessmentServiceImpl.SEMANTIC_STEP, context());

        CapacityResult saved = new ObjectMapper().convertValue(
                result.checkpointState().get("result"), CapacityResult.class);
        assertThat(saved.status()).isEqualTo("requires_long_context");
        assertThat(saved.degradedReason()).isEqualTo("provider_context_insufficient");
        verify(provider, never()).generate(any());
    }

    @Test
    void degradesInvalidStructuredOutputWithoutPersistingModelClaims() throws Exception {
        CapacityResult fallback = result("fits", "invalid_model_output");
        when(provider.capabilities()).thenReturn(new LlmProviderCapabilities(false, true, false, 32000, 4096));
        when(service.requiresLongContext(8L, 32000)).thenReturn(false);
        when(service.semanticSource(8L)).thenReturn("{}");
        when(provider.generate(any())).thenReturn(new com.dugnan.moqi.llm.LlmResponse(
                null, new ObjectMapper().readTree("{}"), null));
        when(service.validateSemantic(eq(8L), any(), eq(32000)))
                .thenThrow(new IllegalArgumentException("非法容量契约"));
        when(service.fallback(8L, "invalid_model_output")).thenReturn(fallback);

        var result = workflow.execute(ChapterCapacityAssessmentServiceImpl.SEMANTIC_STEP, context());

        CapacityResult saved = new ObjectMapper().convertValue(
                result.checkpointState().get("result"), CapacityResult.class);
        assertThat(saved.status()).isEqualTo("fits");
        assertThat(saved.degradedReason()).isEqualTo("invalid_model_output");
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(51L, 61L, ChapterCapacityAssessmentServiceImpl.SEMANTIC_STEP,
                1, "effect", Map.of("assessmentId", 8L, "workId", 2L, "aiTaskId", 41L),
                Map.of("assessmentId", 8L), Map.of(), new AgentRunCallRegistry());
    }

    private CapacityResult result(String status, String reason) {
        return new CapacityResult(status, 1200, 1800, List.of("原因"), List.of(), List.of(), List.of(),
                List.of(), List.of(), "fallback", reason, "requires_long_context".equals(status));
    }
}
