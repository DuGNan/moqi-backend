package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.service.ChapterConsensusTaskService;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;
import com.dugnan.moqi.llm.LlmProviderCapabilities;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 验证章节共识成熟度工作流的收束与安全拦截行为。
 */
@ExtendWith(MockitoExtension.class)
class ChapterConsensusMaturityWorkflowDefinitionTest {
    @Mock private ChapterConversationMessageMapper messageMapper;
    @Mock private AiTaskMapper taskMapper;
    @Mock private ChapterBriefMapper briefMapper;
    @Mock private ChapterConsensusTaskService consensusTaskService;
    @Mock private LlmProviderFactory providerFactory;
    @Mock private UserConfigService userConfigService;
    @Mock private LlmProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChapterConsensusMaturityWorkflowDefinition workflow;

    @BeforeEach
    void setUp() {
        workflow = new ChapterConsensusMaturityWorkflowDefinition(messageMapper, taskMapper, briefMapper,
                consensusTaskService, providerFactory, userConfigService, objectMapper, 2, 0.75D, 120, 0, 12000);
        org.mockito.Mockito.lenient().when(provider.capabilities())
                .thenReturn(new LlmProviderCapabilities(false, true, false, 32768, 4096));
    }

    @Test
    void doesNotEvaluateWhenDiscussionIsNotMatureEnough() throws Exception {
        when(messageMapper.selectList(any())).thenReturn(List.of(message(11L, "user")));

        var result = workflow.execute("precheck", context());

        assertThat(result.nextStepKey()).isNull();
        assertThat(result.outputSummary().get("reasonCodes")).isEqualTo(List.of("INSUFFICIENT_NEW_MESSAGES"));
        verify(providerFactory, never()).create(any());
    }

    @Test
    void keepsLowConfidenceResultOutOfEnqueueStep() throws Exception {
        when(messageMapper.selectList(any())).thenReturn(List.of(message(11L, "user"), message(12L, "assistant")));
        when(userConfigService.requireAvailableModelConfig()).thenReturn(new LlmProviderRuntimeConfig("fake", "", "", "test"));
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.generate(any())).thenReturn(response("""
                {"schemaVersion":1,"ready":true,"confidence":0.5,"changedDecisionKeys":[],"evidenceMessageIds":[11],"reasonCodes":["LOW_CONFIDENCE"]}
                """));

        var result = workflow.execute("evaluate", context());

        assertThat(result.nextStepKey()).isNull();
        assertThat(result.outputSummary().get("ready")).isEqualTo(false);
        verify(consensusTaskService, never()).createAutoTask(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsEvidenceOutsideCurrentConversation() {
        when(messageMapper.selectList(any())).thenReturn(List.of(message(11L, "user"), message(12L, "assistant")));
        when(userConfigService.requireAvailableModelConfig()).thenReturn(new LlmProviderRuntimeConfig("fake", "", "", "test"));
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.generate(any())).thenReturn(response("""
                {"schemaVersion":1,"ready":true,"confidence":0.9,"changedDecisionKeys":[],"evidenceMessageIds":[999],"reasonCodes":[]}
                """));

        assertThatThrownBy(() -> workflow.execute("evaluate", context()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("无效证据");
    }

    @Test
    void rejectsInvalidMaturityStructure() {
        when(messageMapper.selectList(any())).thenReturn(List.of(message(11L, "user"), message(12L, "assistant")));
        when(userConfigService.requireAvailableModelConfig()).thenReturn(new LlmProviderRuntimeConfig("fake", "", "", "test"));
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.generate(any())).thenReturn(response("{" + "\"ready\":true}"));

        assertThatThrownBy(() -> workflow.execute("evaluate", context()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("结构化契约");
    }

    @Test
    void doesNotEnqueueWhenAReplyArrivedAfterEvaluationInput() throws Exception {
        when(messageMapper.selectList(any())).thenReturn(List.of(message(11L, "user"), message(12L, "assistant"),
                message(13L, "user")));

        var result = workflow.execute("enqueue_consensus", context());

        assertThat(result.outputSummary()).containsEntry("stale", true);
        verify(consensusTaskService, never()).createAutoTask(any(), any(), any(), any(), any(), any(), any());
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(71L, 72L, "step", 1, "effect", Map.of("chapterId", 2L,
                "conversationId", 8L, "assistantMessageId", 12L), Map.of(), Map.of(), null);
    }

    private ChapterConversationMessageEntity message(Long id, String role) {
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setId(id);
        message.setMessageRole(role);
        message.setChapterId(2L);
        message.setConversationId(8L);
        message.setContent("消息" + id);
        message.setDeleted(0);
        return message;
    }

    private LlmResponse response(String json) {
        try {
            return new LlmResponse(null, objectMapper.readTree(json), null);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
