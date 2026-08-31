package com.dugnan.moqi.chapter.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ConversationHistoryMessage;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmRole;

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
        when(service.modelPrompt(9L)).thenReturn("需要处理的正文：原文");
        when(provider.generate(any())).thenReturn(new LlmResponse(null,
                objectMapper.readTree("{\"replacement\":\"候选正文\",\"planningProposal\":null}"), null));
        SelectionAssistanceWorkflowDefinition workflow = new SelectionAssistanceWorkflowDefinition(
                service, providerFactory, configService, objectMapper);

        workflow.execute(SelectionAssistanceServiceImpl.GENERATE_STEP, context());

        verify(service).complete(9L, "候选正文", "review_required", List.of(), null,
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
        when(service.modelPrompt(9L)).thenReturn("需要处理的正文：原文");
        when(provider.generate(any())).thenReturn(new LlmResponse(null,
                objectMapper.readTree("{\"advice\":\"建议保留停顿\",\"factRisk\":\"safe\",\"factRiskReasons\":[]}"), null));
        SelectionAssistanceWorkflowDefinition workflow = new SelectionAssistanceWorkflowDefinition(
                service, providerFactory, configService, objectMapper);

        workflow.execute(SelectionAssistanceServiceImpl.GENERATE_STEP, context());

        verify(service).complete(9L, "建议保留停顿", "safe", List.of(), null,
                "agent-step:2:selection-assistance");
    }

    @Test
    void passesOptionalPlanningProposalAsCandidateEvenWhenModelMarksFactRiskSafe() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SelectionAssistanceServiceImpl service = mock(SelectionAssistanceServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService configService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(configService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.operation(9L)).thenReturn("rewrite");
        when(service.sourceFingerprint(9L)).thenReturn("f".repeat(64));
        when(service.modelPrompt(9L)).thenReturn("当前权威场景规划摘要：共 1 场");
        when(provider.generate(any())).thenReturn(new LlmResponse(null, objectMapper.readTree("""
                {
                  "replacement":"改写候选",
                  "factRisk":"safe",
                  "factRiskReasons":[],
                  "planningProposal":{
                    "changeReason":"改写改变了场景结果",
                    "beforeSummary":"共 1 场",
                    "afterSummary":"共 1 场，结果已调整",
                    "scenes":[{
                      "sceneKey":"scene-1","sequence":1,"title":"第一场","timeAnchor":"当天",
                      "goal":"推进目标","conflict":"发生冲突","emotion":"紧张","pacing":"快速",
                      "expectedOutcome":"新结果","status":"planned","narrativeWeight":"core"
                    }]
                  }
                }
                """), null));
        SelectionAssistanceWorkflowDefinition workflow = new SelectionAssistanceWorkflowDefinition(
                service, providerFactory, configService, objectMapper);

        workflow.execute(SelectionAssistanceServiceImpl.GENERATE_STEP, context());

        ArgumentCaptor<SelectionAssistanceModels.ModelPlanningProposal> proposal =
                ArgumentCaptor.forClass(SelectionAssistanceModels.ModelPlanningProposal.class);
        verify(service).complete(org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq("改写候选"), org.mockito.ArgumentMatchers.eq("safe"),
                org.mockito.ArgumentMatchers.eq(List.of()), proposal.capture(),
                org.mockito.ArgumentMatchers.eq("agent-step:2:selection-assistance"));
        assertThat(proposal.getValue().changeReason()).isEqualTo("改写改变了场景结果");
        assertThat(proposal.getValue().scenes()).hasSize(1);
    }

    @Test
    void rejectsPlanningProposalForDiscussionAndUnknownOutputFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SelectionAssistanceServiceImpl service = mock(SelectionAssistanceServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService configService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(configService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.operation(9L)).thenReturn("discuss");
        when(service.sourceFingerprint(9L)).thenReturn("f".repeat(64));
        when(service.modelPrompt(9L)).thenReturn("讨论正文");
        when(provider.generate(any())).thenReturn(new LlmResponse(null, objectMapper.readTree("""
                {"advice":"建议","factRisk":"safe","factRiskReasons":[],
                 "planningProposal":{"changeReason":"原因","beforeSummary":"前","afterSummary":"后","scenes":[]}}
                """), null));
        SelectionAssistanceWorkflowDefinition workflow = new SelectionAssistanceWorkflowDefinition(
                service, providerFactory, configService, objectMapper);

        assertThatThrownBy(() -> workflow.execute(SelectionAssistanceServiceImpl.GENERATE_STEP, context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("契约外字段");

        when(service.operation(9L)).thenReturn("rewrite");
        when(provider.generate(any())).thenReturn(new LlmResponse(null, objectMapper.readTree(
                "{\"replacement\":\"候选\",\"factRisk\":\"safe\",\"factRiskReasons\":[],\"unexpected\":true}"), null));
        assertThatThrownBy(() -> workflow.execute(SelectionAssistanceServiceImpl.GENERATE_STEP, context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("契约外字段");
    }

    @Test
    void sendsOrderedSystemAndNaturalLanguageUserMessagesToProvider() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SelectionAssistanceServiceImpl service = mock(SelectionAssistanceServiceImpl.class);
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        UserConfigService configService = mock(UserConfigService.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(configService.requireAvailableExecutionConfig()).thenReturn(mock(LlmExecutionConfig.class));
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(service.operation(9L)).thenReturn("rewrite");
        when(service.sourceFingerprint(9L)).thenReturn("f".repeat(64));
        when(service.modelHistory(9L)).thenReturn(List.of(
                new ConversationHistoryMessage("user", "上一轮作者消息"),
                new ConversationHistoryMessage("assistant", "上一轮 Moqi 建议")));
        when(service.modelPrompt(9L)).thenReturn("本轮任务：按作者要求重写正文\n需要处理的正文：原文");
        when(provider.generate(any())).thenReturn(new LlmResponse(null,
                objectMapper.readTree("{\"replacement\":\"候选\",\"factRisk\":\"safe\",\"factRiskReasons\":[]}"), null));
        SelectionAssistanceWorkflowDefinition workflow = new SelectionAssistanceWorkflowDefinition(
                service, providerFactory, configService, objectMapper);

        workflow.execute(SelectionAssistanceServiceImpl.GENERATE_STEP, context());

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).generate(request.capture());
        assertThat(request.getValue().messages()).hasSize(4);
        assertThat(request.getValue().messages().get(0).role()).isEqualTo(LlmRole.SYSTEM);
        assertThat(request.getValue().messages().get(0).content())
                .contains("待作者应用和保存的候选")
                .contains("历史助手回复只是候选建议");
        assertThat(request.getValue().messages().get(1).role()).isEqualTo(LlmRole.USER);
        assertThat(request.getValue().messages().get(1).content()).isEqualTo("上一轮作者消息");
        assertThat(request.getValue().messages().get(2).role()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(request.getValue().messages().get(2).content()).isEqualTo("上一轮 Moqi 建议");
        assertThat(request.getValue().messages().get(3).role()).isEqualTo(LlmRole.USER);
        assertThat(request.getValue().messages().get(3).content())
                .isEqualTo("本轮任务：按作者要求重写正文\n需要处理的正文：原文")
                .doesNotContain("operation", "targetKind", "requestStatus");
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(1L, 2L, SelectionAssistanceServiceImpl.GENERATE_STEP, 1, "effect",
                Map.of("assistanceId", 9L, "workId", 1L, "chapterId", 2L, "aiTaskId", 8L), Map.of(), Map.of(), null);
    }
}
