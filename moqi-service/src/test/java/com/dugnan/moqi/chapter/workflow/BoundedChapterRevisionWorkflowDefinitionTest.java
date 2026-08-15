package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.chapter.service.impl.BoundedChapterRevisionServiceImpl;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmResponse;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证整章有界修订只生成候选并强制进入重新评价步骤。
 */
class BoundedChapterRevisionWorkflowDefinitionTest {

    @Test
    void routesStructuredRevisionThroughCandidateAndReEvaluation() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        BoundedChapterRevisionServiceImpl service = mock(BoundedChapterRevisionServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService configService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(configService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.workflowInput(7L)).thenReturn(Map.of(
                "revisionBrief", Map.of("findings", java.util.List.of("cause-1", "route-1")),
                "originalContent", "原正文"));
        when(provider.generate(any())).thenReturn(new LlmResponse(null,
                objectMapper.readTree("{\"revisionContent\":\"修订后的完整正文\"}"), null));
        BoundedChapterRevisionWorkflowDefinition workflow = new BoundedChapterRevisionWorkflowDefinition(
                service, providerFactory, configService, objectMapper);
        AgentStepExecutionContext context = new AgentStepExecutionContext(1L, 2L, "revise", 1,
                "effect", Map.of("revisionId", 7L), Map.of("revisionId", 7L), Map.of(), null);

        var revised = workflow.execute("revise", context);

        assertThat(revised.nextStepKey()).isEqualTo("persist_candidate");
        assertThat(revised.checkpointState()).containsEntry("revisionContent", "修订后的完整正文");
        var evaluation = workflow.execute("start_re_evaluation", context);
        assertThat(evaluation.nextStepKey()).isEqualTo("finalize");
    }

    @Test
    void failureLeavesCandidateUnadoptedAndRecoverable() {
        BoundedChapterRevisionServiceImpl service = mock(BoundedChapterRevisionServiceImpl.class);
        BoundedChapterRevisionWorkflowDefinition workflow = new BoundedChapterRevisionWorkflowDefinition(
                service, mock(LlmProviderFactory.class), mock(UserConfigService.class), new ObjectMapper());
        AgentStepExecutionContext context = new AgentStepExecutionContext(1L, 2L, "revise", 2,
                "effect", Map.of("revisionId", 7L), Map.of(), Map.of(), null);

        workflow.applyFailure("revise", context, new IllegalStateException("provider failed"));

        verify(service).fail(7L, "revise");
    }
}
