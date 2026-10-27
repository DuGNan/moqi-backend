package com.dugnan.moqi.chapter.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.entity.ProseConversationMemoryEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.mapper.ProseConversationMemoryMapper;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmResponse;

/**
 * @author dgn
 * @date 2026-10-25
 * @description 验证增量摘要的轮次预算、来源约束、失败保留与并发保护。
 */
class ProseConversationMemoryServiceTest {
    private final ProseConversationMemoryMapper memories = mock(ProseConversationMemoryMapper.class);
    private final ChapterConversationMessageMapper messages = mock(ChapterConversationMessageMapper.class);
    private final LlmProvider provider = mock(LlmProvider.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ProseConversationMemoryService service = new ProseConversationMemoryService(memories, messages, json);

    @Test
    void auditsActualSummaryAttemptsIncludingFailedValidation() throws Exception {
        var calls = mock(com.dugnan.moqi.chapter.mapper.ProseExecutionCallMapper.class);
        service.setExecutionCalls(calls);
        var history = rounds(13);
        when(messages.selectList(any())).thenReturn(history, history.subList(14, 26));
        when(provider.generate(any())).thenReturn(summary("author_decision", 2), summary("focus", 1));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, com.dugnan.moqi.chapter.entity.ProseExecutionCallEntity.class).setId(50L);
            return 1;
        }).when(calls).insert(any(com.dugnan.moqi.chapter.entity.ProseExecutionCallEntity.class));
        service.prepare(10L, 100L, provider, 9L);
        var audits = ArgumentCaptor.forClass(com.dugnan.moqi.chapter.entity.ProseExecutionCallEntity.class);
        verify(calls, times(2)).updateById(audits.capture());
        assertThat(audits.getAllValues()).extracting(call -> call.getValidationStatus())
                .containsExactly("failed", "passed");
        assertThat(audits.getAllValues().get(1).getRepairOfId()).isEqualTo(50L);
        var requests = ArgumentCaptor.forClass(com.dugnan.moqi.llm.LlmRequest.class);
        verify(provider, times(2)).generate(requests.capture());
        for (int index = 0; index < 2; index++) {
            assertThat(audits.getAllValues().get(index).getMessagesJson())
                    .isEqualTo(json.writeValueAsString(requests.getAllValues().get(index).messages()));
        }
    }

    @Test
    void shortHistoryKeepsRolesWithoutCallingSummary() {
        when(messages.selectList(any())).thenReturn(rounds(2));
        var context = service.prepare(10L, 100L, provider);
        assertThat(context.turns()).hasSize(2);
        assertThat(context.summary()).isEmpty();
        verify(provider, never()).generate(any());
        verify(memories, never()).insert(any(ProseConversationMemoryEntity.class));
    }

    @Test
    void compressesOldCompleteRoundsAndRetainsSixRecentRounds() throws Exception {
        var history = rounds(13);
        when(messages.selectList(any())).thenReturn(history, history.subList(14, 26));
        when(provider.generate(any())).thenReturn(summary("focus", 1));
        var context = service.prepare(10L, 100L, provider);
        assertThat(context.turns()).hasSize(6);
        assertThat(context.summary()).contains("讨论焦点", "不是权威事实");
        var saved = ArgumentCaptor.forClass(ProseConversationMemoryEntity.class);
        verify(memories).insert(saved.capture());
        assertThat(saved.getValue().getThroughMessageId()).isEqualTo(14L);
        assertThat(saved.getValue().getConversationId()).isEqualTo(10L);
        assertThat(saved.getValue().getSourceFingerprint()).hasSize(64);
        assertThat(saved.getValue().getSourceSnapshotJson()).contains("sourceMessageId", "previous");
    }

    @Test
    void retriesOnceAndDoesNotPersistFailedSummary() {
        when(messages.selectList(any())).thenReturn(rounds(13));
        when(provider.generate(any())).thenThrow(new IllegalStateException("provider unavailable"));
        assertThatThrownBy(() -> service.prepare(10L, 100L, provider))
                .isInstanceOf(ProseConversationMemoryService.ContextUnavailableException.class);
        verify(provider, times(2)).generate(any());
        verify(memories, never()).insert(any(ProseConversationMemoryEntity.class));
        verify(memories, never()).update(any(), any());
        verify(messages, never()).delete(any());
    }

    @Test
    void rejectsAssistantSuggestionAsAuthorDecision() throws Exception {
        when(messages.selectList(any())).thenReturn(rounds(13));
        when(provider.generate(any())).thenReturn(summary("author_decision", 2));
        assertThatThrownBy(() -> service.prepare(10L, 100L, provider))
                .isInstanceOf(ProseConversationMemoryService.ContextUnavailableException.class);
        verify(provider, times(2)).generate(any());
        verify(memories, never()).insert(any(ProseConversationMemoryEntity.class));
    }

    @Test
    void staleVersionCannotOverwriteExistingMemory() throws Exception {
        var memory = new ProseConversationMemoryEntity();
        memory.setId(5L);
        memory.setConversationId(10L);
        memory.setThroughMessageId(0L);
        memory.setVersion(3);
        memory.setSummaryJson("{\"entries\":[]}");
        when(memories.selectOne(any())).thenReturn(memory);
        when(messages.selectList(any())).thenReturn(rounds(13));
        when(provider.generate(any())).thenReturn(summary("focus", 1));
        when(memories.update(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.prepare(10L, 100L, provider))
                .isInstanceOf(ProseConversationMemoryService.ContextUnavailableException.class);
        assertThat(memory.getThroughMessageId()).isZero();
        assertThat(memory.getVersion()).isEqualTo(3);
    }

    @Test
    void refusesUnknownSourcesAndOversizedSummary() throws Exception {
        assertThatThrownBy(() -> service.validate(summary("focus", 99).structuredContent(), Set.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
        var tree = json.readTree("{\"entries\":[{\"kind\":\"focus\",\"text\":\""
                + "长".repeat(6000) + "\",\"sourceMessageIds\":[1]}]}");
        assertThatThrownBy(() -> service.validate(tree, Set.of(1L))).isInstanceOf(IllegalArgumentException.class);
    }

    private LlmResponse summary(String kind, int source) throws Exception {
        return new LlmResponse(null, json.readTree("{\"entries\":[{\"kind\":\"" + kind
                + "\",\"text\":\"保留跳伞过程\",\"sourceMessageIds\":[" + source + "]}]}"), null);
    }

    private List<ChapterConversationMessageEntity> rounds(int count) {
        List<ChapterConversationMessageEntity> result = new ArrayList<>();
        for (int index = 1; index <= count * 2; index++) {
            var message = new ChapterConversationMessageEntity();
            message.setId((long) index);
            message.setConversationId(10L);
            message.setMessageRole(index % 2 == 1 ? "user" : "assistant");
            message.setContent("讨论跳伞片段 " + index);
            message.setGenerationStatus("completed");
            result.add(message);
        }
        return result;
    }
}
