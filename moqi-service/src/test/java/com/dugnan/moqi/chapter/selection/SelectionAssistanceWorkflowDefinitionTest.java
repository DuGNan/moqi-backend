package com.dugnan.moqi.chapter.selection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmResponse;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证选区协助工作流的结构化候选和默认事实风险边界。
 */
class SelectionAssistanceWorkflowDefinitionTest {

    @Test
    void persistsRewriteAsReviewRequiredWhenModelOmitsRisk() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SelectionAssistanceServiceImpl service = mock(SelectionAssistanceServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService configService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(configService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.operation(9L)).thenReturn("rewrite");
        when(service.sourceFingerprint(9L)).thenReturn("f".repeat(64));
        when(service.modelInput(9L)).thenReturn(Map.of("selectedText", "原文"));
        when(provider.generate(any())).thenReturn(new LlmResponse(null,
                objectMapper.readTree("{\"replacement\":\"候选正文\"}"), null));
        SelectionAssistanceWorkflowDefinition workflow = new SelectionAssistanceWorkflowDefinition(
                service, providerFactory, configService, objectMapper);

        workflow.execute(SelectionAssistanceServiceImpl.GENERATE_STEP, context());

        verify(service).complete(9L, "候选正文", "review_required", List.of(),
                "agent-step:2:selection-assistance");
    }

    @Test
    void keepsDiscussionAsAdviceInsteadOfReplacement() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SelectionAssistanceServiceImpl service = mock(SelectionAssistanceServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService configService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(configService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.operation(9L)).thenReturn("discuss");
        when(service.sourceFingerprint(9L)).thenReturn("f".repeat(64));
        when(service.modelInput(9L)).thenReturn(Map.of("selectedText", "原文"));
        when(provider.generate(any())).thenReturn(new LlmResponse(null,
                objectMapper.readTree("{\"advice\":\"建议保留停顿\",\"factRisk\":\"safe\",\"factRiskReasons\":[]}"), null));
        SelectionAssistanceWorkflowDefinition workflow = new SelectionAssistanceWorkflowDefinition(
                service, providerFactory, configService, objectMapper);

        workflow.execute(SelectionAssistanceServiceImpl.GENERATE_STEP, context());

        verify(service).complete(9L, "建议保留停顿", "safe", List.of(),
                "agent-step:2:selection-assistance");
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(1L, 2L, SelectionAssistanceServiceImpl.GENERATE_STEP, 1, "effect",
                Map.of("assistanceId", 9L, "workId", 1L, "chapterId", 2L, "aiTaskId", 8L), Map.of(), Map.of(), null);
    }
}
