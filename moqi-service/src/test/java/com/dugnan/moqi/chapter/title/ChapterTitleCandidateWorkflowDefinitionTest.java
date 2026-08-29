package com.dugnan.moqi.chapter.title;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 验证章节取名工作流只接受恰好三个标题的严格 JSON 契约。
 */
class ChapterTitleCandidateWorkflowDefinitionTest {

    @Test
    void persistsExactlyThreeStructuredCandidates() throws Exception {
        Fixture fixture = fixture("{\"titles\":[\"潮痕\",\"雾中来客\",\"失约的钟声\"]}");

        fixture.workflow().execute(ChapterTitleCandidateServiceImpl.GENERATE_STEP, context());

        verify(fixture.service()).markRunning(9L, 1);
        verify(fixture.service()).complete(9L, List.of("潮痕", "雾中来客", "失约的钟声"));
        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(fixture.provider()).generate(request.capture());
        assertThat(request.getValue().messages().get(0).content())
                .contains("候选", "不得输出解释", "不得声称已采用");
    }

    @Test
    void rejectsWrongCandidateCountAndUnknownFields() throws Exception {
        Fixture tooFew = fixture("{\"titles\":[\"潮痕\",\"雾中来客\"]}");
        assertThatThrownBy(() -> tooFew.workflow().execute(
                ChapterTitleCandidateServiceImpl.GENERATE_STEP, context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 个标题");

        Fixture unknown = fixture("{\"titles\":[\"甲\",\"乙\",\"丙\"],\"reasoning\":\"隐藏推理\"}");
        assertThatThrownBy(() -> unknown.workflow().execute(
                ChapterTitleCandidateServiceImpl.GENERATE_STEP, context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("唯一 titles 字段");
    }

    @Test
    void canceledBatchStopsBeforeCallingProvider() {
        Fixture fixture;
        try {
            fixture = fixture("{\"titles\":[\"甲\",\"乙\",\"丙\"]}");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        when(fixture.service().markRunning(9L, 1)).thenReturn(false);

        assertThatThrownBy(() -> fixture.workflow().execute(
                ChapterTitleCandidateServiceImpl.GENERATE_STEP, context()))
                .isInstanceOf(CancellationException.class);
        verify(fixture.provider(), org.mockito.Mockito.never()).generate(any());
    }

    private Fixture fixture(String json) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ChapterTitleCandidateServiceImpl service = mock(ChapterTitleCandidateServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService configService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(configService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.markRunning(9L, 1)).thenReturn(true);
        when(service.sourceFingerprint(9L)).thenReturn("f".repeat(64));
        when(service.modelPrompt(9L)).thenReturn("作品：潮汐边界\n需要取名的已保存正文：正文");
        when(provider.generate(any())).thenReturn(new LlmResponse(null, objectMapper.readTree(json), null));
        return new Fixture(new ChapterTitleCandidateWorkflowDefinition(service, providerFactory, configService),
                service, provider);
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(1L, 2L, ChapterTitleCandidateServiceImpl.GENERATE_STEP, 1, "effect",
                Map.of("batchId", 9L, "workId", 1L, "chapterId", 2L, "aiTaskId", 8L),
                Map.of(), Map.of(), null);
    }

    private record Fixture(
            ChapterTitleCandidateWorkflowDefinition workflow,
            ChapterTitleCandidateServiceImpl service,
            LlmProvider provider) {
    }
}
