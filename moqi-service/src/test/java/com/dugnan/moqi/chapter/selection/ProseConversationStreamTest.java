package com.dugnan.moqi.chapter.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import com.dugnan.moqi.chapter.stream.ChapterReplyEvent;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamCallRegistry;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;

/**
 * @author dgn
 * @date 2026-10-22
 * @description 验证对话改写的增量解码、建议分流和不完整结果保护。
 */
class ProseConversationStreamTest {
    @Test
    void clarificationRemainsDiscussionAndNeverEdits() throws Exception {
        Fixture fixture = new Fixture(List.of("{\"clarification\":\"你指的是哪段？\"}"));
        fixture.run();
        verify(fixture.service).completeConversation(1L, "【需要澄清】\n你指的是哪段？", false, "call");
    }

    @Test
    void acceptsProviderLineBreaksWithoutAcceptingTruncatedJson() throws Exception {
        Fixture fixture = new Fixture(List.of("{\"advice\":\"第一段\n第二段\"}"));
        fixture.run();
        verify(fixture.service).completeConversation(1L, "第一段\n第二段", false, "call");
    }

    @Test
    void rejectsRepeatedFieldsAndTrailingObjects() {
        for (String output : List.of("{\"replacement\":\"甲\",\"replacement\":\"乙\"}",
                "{\"replacement\":\"甲\"}{\"advice\":\"乙\"}")) {
            Fixture fixture = new Fixture(List.of(output));
            assertThatThrownBy(fixture::run).isInstanceOf(Exception.class);
            verify(fixture.service, never()).completeConversation(any(), anyString(), anyBoolean(), anyString());
        }
    }

    @Test
    void decodesOnlyProseAndWaitsForSplitEscapes() {
        assertThat(ProseConversationStream.visibleText("{\"replacement\":\"风\\n变")) .isEqualTo("风\n变");
        assertThat(ProseConversationStream.visibleText("{\"advice\":\"风\\u53")) .isEqualTo("风");
        assertThat(ProseConversationStream.visibleText("{\"advice\":\"风\\u53d8\",\"secret\":\"x")) .isEqualTo("风变");
        assertThat(ProseConversationStream.visibleText("{\"reasoning\":\"隐藏")) .isEmpty();
    }

    @Test
    void publishesDeltasBeforePersistingValidatedReplacement() throws Exception {
        Fixture fixture = new Fixture(List.of("{\"replacement\":\"风", "变了。\"}"));
        fixture.run();
        assertThat(fixture.events.stream().filter(event -> event.type().equals("reply.delta"))
                .map(ChapterReplyEvent::text)).containsExactly("风", "变了。");
        verify(fixture.service).completeConversation(1L, "风变了。", true, "call");
    }

    @Test
    void adviceNeverBecomesReplacement() throws Exception {
        Fixture fixture = new Fixture(List.of("{\"advice\":\"这段可以压缩。\"}"));
        fixture.run();
        verify(fixture.service).completeConversation(1L, "这段可以压缩。", false, "call");
    }

    @Test
    void incompleteJsonCannotBeApplied() {
        Fixture fixture = new Fixture(List.of("{\"replacement\":\"未完成"));
        assertThatThrownBy(fixture::run).isInstanceOf(Exception.class);
        verify(fixture.service, never()).completeConversation(any(), anyString(), anyBoolean(), anyString());
    }

    private static final class Fixture {
        private final SelectionAssistanceServiceImpl service = mock(SelectionAssistanceServiceImpl.class);
        private final LlmProvider provider = mock(LlmProvider.class);
        private final List<ChapterReplyEvent> events = new ArrayList<>();
        private final ProseConversationStream stream;

        private Fixture(List<String> chunks) {
            SelectionAssistanceModels.View view = mock(SelectionAssistanceModels.View.class);
            when(view.chapterId()).thenReturn(2L);
            when(view.aiTaskId()).thenReturn(3L);
            when(view.conversationId()).thenReturn(4L);
            when(view.assistantMessageId()).thenReturn(5L);
            when(service.get(1L)).thenReturn(view);
            ApplicationEventPublisher publisher = event -> events.add((ChapterReplyEvent) event);
            stream = new ProseConversationStream(service, publisher, new LlmStreamCallRegistry(), new ObjectMapper());
            LlmStreamCall call = mock(LlmStreamCall.class);
            when(call.await()).thenReturn(new LlmStreamResult(LlmStreamStatus.COMPLETED, null, null));
            when(provider.stream(any(), any())).thenAnswer(invocation -> {
                Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
                chunks.forEach(chunk -> consumer.accept(new LlmStreamEvent.TextDelta(chunk)));
                verify(service, never()).completeConversation(eq(1L), anyString(), anyBoolean(), anyString());
                return call;
            });
        }

        private void run() throws Exception {
            stream.generate(1L, provider, mock(LlmRequest.class), "call");
        }
    }
}
