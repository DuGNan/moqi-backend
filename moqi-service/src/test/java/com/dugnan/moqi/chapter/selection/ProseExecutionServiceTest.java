package com.dugnan.moqi.chapter.selection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.dugnan.moqi.chapter.entity.ProseExecutionCallEntity;
import com.dugnan.moqi.chapter.mapper.ProseExecutionCallMapper;
import com.dugnan.moqi.chapter.stream.ChapterReplyEvent;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderCapabilities;
import com.dugnan.moqi.llm.LlmRole;
import com.dugnan.moqi.llm.LlmResponseMetadata;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamCallRegistry;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author dgn
 * @date 2026-10-25
 * @description 验证两步执行的真实交付、有限修复及原子修改边界。
 */
class ProseExecutionServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EDIT_PLAN = """
            {"task":"edit","scope":"partial","target":"current","goal":"改善动作衔接",
            "authorization":"改一下","basis":"开头","question":"","options":[]}
            """;

    @Test
    void replacesPromiseWithValidatedDeliveryAndAuditsEveryCall() throws Exception {
        Fixture fixture = new Fixture(EDIT_PLAN, "{\"text\":\"我来修改\",\"kind\":\"discussion\"}",
                "{\"edits\":[{\"original\":\"他走了。\",\"replacement\":\"陈默转身离开。\"}],\"kind\":\"edit\"}");
        fixture.run();
        verify(fixture.assistance).completeExecution(1L, "陈默转身离开。后文。", true,
                "已生成正文修改。", "call");
        assertThat(JSON.readTree(fixture.records.get(2).getMessagesJson()).size()).isEqualTo(4);
        verify(fixture.provider, times(3)).stream(any(), any());
        assertThat(fixture.records).hasSize(3);
        assertThat(fixture.records.get(2).getRepairOfId()).isEqualTo(2L);
        assertThat(fixture.records.get(1).getRawOutput()).contains("我来修改");
    }

    @Test
    void failureNeverPartiallyAppliesAndDoesNotAskAuthorToRestateIntent() {
        Fixture fixture = new Fixture(EDIT_PLAN,
                "{\"edits\":[{\"original\":\"聊天稿\",\"replacement\":\"新文\"}],\"kind\":\"edit\"}",
                "{\"edits\":[{\"original\":\"仍不匹配\",\"replacement\":\"新文\"}],\"kind\":\"edit\"}");
        assertThatThrownBy(fixture::run).hasMessageContaining("本轮未修改");
        verify(fixture.assistance, never()).completeExecution(any(), anyString(), anyBoolean(), any(), anyString());
        verify(fixture.provider, times(3)).stream(any(), any());
        assertThat(fixture.events).noneMatch(event -> "reply.completed".equals(event.type()));
    }

    @Test
    void planningAndExecutionShareOneRepairBudget() {
        Fixture fixture = new Fixture(EDIT_PLAN.replace("\"task\"", "\"type\""), EDIT_PLAN,
                "{\"kind\":\"discussion\",\"text\":\"稍后再改\"}");
        assertThatThrownBy(fixture::run).hasMessageContaining("本轮未修改");
        verify(fixture.provider, times(3)).stream(any(), any());
        verify(fixture.assistance, never()).completeExecution(any(), anyString(), anyBoolean(), any(), anyString());
    }

    @Test
    void emptyPlanningOutputUsesExistingRepairWithoutInventingAssistantMessage() throws Exception {
        Fixture fixture = new Fixture("", EDIT_PLAN,
                "{\"kind\":\"edit\",\"edits\":[{\"original\":\"他走了。\",\"replacement\":\"陈默离开了。\"}]}");
        fixture.run();
        verify(fixture.provider, times(3)).stream(any(), any());
        var repair = JSON.readTree(fixture.records.get(1).getMessagesJson());
        assertThat(repair.toString()).doesNotContain("ASSISTANT");
        assertThat(fixture.records.get(0).getRawOutput()).isEmpty();
    }

    @Test
    void emptyExecutionOutputUsesOnlyOneRepairAndCanDeliver() throws Exception {
        Fixture fixture = new Fixture(EDIT_PLAN, "",
                "{\"kind\":\"edit\",\"edits\":[{\"original\":\"他走了。\",\"replacement\":\"陈默离开了。\"}]}");
        fixture.run();
        verify(fixture.provider, times(3)).stream(any(), any());
        assertThat(fixture.records.get(2).getMessagesJson()).doesNotContain("ASSISTANT");
    }

    @Test
    void currentStageContractFollowsHistoryWithoutChangingItsRoles() throws Exception {
        Fixture fixture = new Fixture(EDIT_PLAN,
                "{\"kind\":\"edit\",\"edits\":[{\"original\":\"他走了。\",\"replacement\":\"陈默离开了。\"}]}");
        when(fixture.snapshot.toMessages()).thenReturn(List.of(
                new LlmMessage(LlmRole.SYSTEM, ProseExecutionPrompts.PLAN),
                new LlmMessage(LlmRole.SYSTEM, "当前编辑器全文：他走了。后文。"),
                new LlmMessage(LlmRole.USER, "之前的问题"),
                new LlmMessage(LlmRole.ASSISTANT, "{\"advice\":\"之前的建议\"}"),
                new LlmMessage(LlmRole.USER, "嗯，改一下")));
        fixture.run();
        var planning = JSON.readTree(fixture.records.get(0).getMessagesJson());
        var execution = JSON.readTree(fixture.records.get(1).getMessagesJson());
        assertThat(planning.get(2).path("role").asText()).isEqualTo("ASSISTANT");
        assertThat(planning.get(3).path("content").asText()).isEqualTo(ProseExecutionPrompts.PLAN);
        assertThat(execution.get(3).path("content").asText()).contains("本轮已经确定的执行任务");
        assertThat(planning.get(4)).isEqualTo(execution.get(4));
        assertThat(planning.get(2)).isEqualTo(execution.get(2));
    }

    @Test
    void cancellationBetweenStagesPreventsExecutionAndApplication() {
        Fixture fixture = new Fixture(EDIT_PLAN);
        org.mockito.Mockito.doNothing().doThrow(new IllegalStateException("正文任务已停止"))
                .when(fixture.state).checkRunning(1L);
        assertThatThrownBy(fixture::run).hasMessageContaining("已停止");
        verify(fixture.provider, times(1)).stream(any(), any());
        verify(fixture.assistance, never()).completeExecution(any(), anyString(), anyBoolean(), any(), anyString());
    }

    @Test
    void truncatedOutputCannotApplyEvenWhenJsonLooksComplete() {
        String result = "{\"kind\":\"edit\",\"edits\":[{\"original\":\"他走了。\",\"replacement\":\"他离开了。\"}]}";
        Fixture fixture = new Fixture(EDIT_PLAN, result, result);
        fixture.truncateOutputs = true;
        assertThatThrownBy(fixture::run).hasMessageContaining("本轮未修改");
        verify(fixture.provider, times(3)).stream(any(), any());
        verify(fixture.assistance, never()).completeExecution(any(), anyString(), anyBoolean(), any(), anyString());
    }

    @Test
    void malformedOutputUsesTheSameSingleRepairBudget() throws Exception {
        Fixture fixture = new Fixture(EDIT_PLAN, "{\"edits\":[",
                "{\"text\":\"当前句式已经自然，不需要重复替换\",\"kind\":\"no_change\"}");
        fixture.run();
        verify(fixture.assistance).completeExecution(eq(1L), anyString(), eq(false), eq(null), eq("call"));
        verify(fixture.provider, times(3)).stream(any(), any());
    }

    @Test
    void streamsOnlyNewTextAndPreservesSplitEscapes() {
        assertThat(ProseExecutionService.preview("{\"edits\":[{\"original\":\"不可显示\",\"replacement\":\"风\\u53"))
                .isEqualTo("风");
        assertThat(ProseExecutionService.preview("{\"replacement\":\"风\\u53d8\\n了\",\"kind\":\"edit\"}"))
                .isEqualTo("风变\n了");
        assertThat(ProseExecutionService.preview("{\"kind\":\"draft\",\"text\":\"实际稿件"))
                .isEqualTo("实际稿件");
    }

    @Test
    void repeatedOrOverlappingOriginalsCannotBecomePartialWrites() throws Exception {
        var plan = JSON.readTree(EDIT_PLAN);
        assertThatThrownBy(() -> ProseExecutionService.validateResult(plan,
                JSON.readTree("{\"kind\":\"edit\",\"edits\":[{\"original\":\"甲乙\",\"replacement\":\"新文\"}]}"),
                "甲乙甲乙丙")).hasMessageContaining("重复");
        assertThatThrownBy(() -> ProseExecutionService.validateResult(plan,
                JSON.readTree("{\"kind\":\"edit\",\"edits\":[{\"original\":\"甲乙甲\",\"replacement\":\"新文\"},"
                        + "{\"original\":\"乙甲乙\",\"replacement\":\"另一处\"}]}"),
                "甲乙甲乙丙")).hasMessageContaining("重叠");
    }

    @Test
    void wholeRewriteDoesNotRequireCopyingOldFullText() throws Exception {
        var plan = JSON.readTree(EDIT_PLAN.replace("partial", "whole"));
        assertThat(ProseExecutionService.validateResult(plan,
                JSON.readTree("{\"replacement\":\"全篇新稿\",\"kind\":\"edit\"}"), "旧稿"))
                .isEqualTo("全篇新稿");
        assertThatThrownBy(() -> ProseExecutionService.validatePlan(plan, "仅讨论，不改正文"))
                .hasMessageContaining("授权");
    }

    private static final class Fixture {
        private final SelectionAssistanceServiceImpl assistance = mock(SelectionAssistanceServiceImpl.class);
        private final LlmProvider provider = mock(LlmProvider.class);
        private final ProseExecutionStateService state = mock(ProseExecutionStateService.class);
        private final StoryContextSnapshot snapshot = mock(StoryContextSnapshot.class);
        private boolean truncateOutputs;
        private final List<ProseExecutionCallEntity> records = new ArrayList<>();
        private final List<ChapterReplyEvent> events = new ArrayList<>();
        private final ProseExecutionService service;

        private Fixture(String... outputs) {
            var calls = mock(ProseExecutionCallMapper.class);
            var view = mock(SelectionAssistanceModels.View.class);
            when(view.id()).thenReturn(1L);
            when(view.chapterId()).thenReturn(2L);
            when(view.aiTaskId()).thenReturn(3L);
            when(view.conversationId()).thenReturn(4L);
            when(view.instruction()).thenReturn("嗯，改一下");
            when(view.selectedText()).thenReturn("他走了。后文。");
            when(assistance.get(1L)).thenReturn(view);
            when(assistance.isContinuousConversation(1L)).thenReturn(true);
            when(snapshot.toMessages()).thenReturn(List.of(new LlmMessage(LlmRole.SYSTEM, ProseExecutionPrompts.PLAN),
                    new LlmMessage(LlmRole.SYSTEM, "当前编辑器全文：他走了。后文。")));
            when(assistance.buildModelContext(eq(1L), any(), anyString())).thenReturn(snapshot);
            var capabilities = mock(LlmProviderCapabilities.class);
            when(capabilities.maxOutputTokens()).thenReturn(8192);
            when(provider.capabilities()).thenReturn(capabilities);
            doAnswer(invocation -> {
                ProseExecutionCallEntity row = invocation.getArgument(0);
                row.setId((long) records.size() + 1);
                records.add(row);
                return 1;
            }).when(calls).insert(any(ProseExecutionCallEntity.class));
            Queue<String> queue = new ArrayDeque<>(List.of(outputs));
            when(provider.stream(any(), any())).thenAnswer(invocation -> {
                Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
                String raw = queue.remove();
                consumer.accept(new LlmStreamEvent.TextDelta(raw));
                verify(assistance, never()).completeExecution(any(), anyString(), anyBoolean(), any(), anyString());
                LlmStreamCall call = mock(LlmStreamCall.class);
                LlmResponseMetadata metadata = truncateOutputs && records.size() > 1
                        ? new LlmResponseMetadata(null, null, "length", null, null, null, null, null) : null;
                when(call.await()).thenReturn(new LlmStreamResult(LlmStreamStatus.COMPLETED, metadata, null));
                return call;
            });
            service = new ProseExecutionService(assistance, state, calls, JSON, new LlmStreamCallRegistry(),
                    event -> events.add((ChapterReplyEvent) event));
        }

        private void run() throws Exception {
            service.generate(1L, provider, "call");
        }
    }
}
