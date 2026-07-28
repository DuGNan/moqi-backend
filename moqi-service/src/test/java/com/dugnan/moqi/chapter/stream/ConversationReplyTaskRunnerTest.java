package com.dugnan.moqi.chapter.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextBuildCommand;
import com.dugnan.moqi.context.StoryContextItem;
import com.dugnan.moqi.context.StoryContextProfile;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextSourceType;
import com.dugnan.moqi.context.StoryContextTaskBindingService;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;
import com.dugnan.moqi.llm.LlmProviderCapabilities;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamCallRegistry;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 验证章节讨论任务使用 Provider V2 流事件并持久化成功回复。
 */
@ExtendWith(MockitoExtension.class)
class ConversationReplyTaskRunnerTest {

    @Mock
    private AiTaskMapper taskMapper;
    @Mock
    private ChapterConversationMessageMapper messageMapper;
    @Mock
    private UserConfigService userConfigService;
    @Mock
    private LlmProviderFactory providerFactory;
    @Mock
    private LlmProvider provider;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private StoryContextTaskBindingService contextBindingService;

    @Test
    void streamsReplyThenPersistsAssistantMessage() {
        AiTaskEntity task = task("queued", 0);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        when(userConfigService.requireAvailableModelConfig())
                .thenReturn(new LlmProviderRuntimeConfig(
                        "deepseek",
                        "https://api.deepseek.com",
                        "test-key",
                        "deepseek-v4-flash"));
        when(providerFactory.create(any())).thenReturn(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("第一段"));
            consumer.accept(new LlmStreamEvent.TextDelta("第二段"));
            return new CompletedCall();
        }).when(provider).stream(any(), any());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper,
                messageMapper,
                userConfigService,
                providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher,
                new LlmStreamCallRegistry()).run(12L);

        ArgumentCaptor<ChapterConversationMessageEntity> messageCaptor =
                ArgumentCaptor.forClass(ChapterConversationMessageEntity.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageRole()).isEqualTo("assistant");
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("第一段第二段");
        verify(eventPublisher).publishEvent(ChapterReplyEvent.completed(2L, 12L, 99L));
    }

    @Test
    void ignoresLateDeltaAndDoesNotPersistAfterCancellation() {
        AiTaskEntity task = task("queued", 0);
        LlmStreamCallRegistry callRegistry = new LlmStreamCallRegistry();
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        when(userConfigService.requireAvailableModelConfig())
                .thenReturn(new LlmProviderRuntimeConfig(
                        "deepseek",
                        "https://api.deepseek.com",
                        "test-key",
                        "deepseek-v4-flash"));
        when(providerFactory.create(any())).thenReturn(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            callRegistry.cancel(12L);
            consumer.accept(new LlmStreamEvent.TextDelta("取消后的迟到增量"));
            return new CanceledCall();
        }).when(provider).stream(any(), any());

        new ConversationReplyTaskRunner(
                taskMapper,
                messageMapper,
                userConfigService,
                providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher,
                callRegistry).run(12L);

        verify(messageMapper, never()).insert(any(ChapterConversationMessageEntity.class));
        verify(eventPublisher, never()).publishEvent(
                ChapterReplyEvent.delta(2L, 12L, "取消后的迟到增量"));
        assertThat(callRegistry.isCancellationRequested(12L)).isFalse();
    }

    @Test
    void buildsAndSendsStoryContextBeforeStreaming() {
        AiTaskEntity task = task("queued", 0);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        when(userConfigService.requireAvailableModelConfig()).thenReturn(
                new LlmProviderRuntimeConfig("deepseek", "https://api.deepseek.com", "test-key", "deepseek-v4-flash"));
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.capabilities()).thenReturn(new LlmProviderCapabilities(true, true, false, 16384, 8192));
        StoryContextSnapshot snapshot = new StoryContextSnapshot(
                88L, "chapter_discussion:1:2:8", 1L, 2L, 8L,
                StoryContextProfile.CHAPTER_DISCUSSION, 1, 1, 16384, 4096, 12288, 12,
                "hash", List.of(
                        new StoryContextItem(StoryContextSourceType.SYSTEM_RULE, "system", "v1", null,
                                "SYSTEM", "系统规则", true, 1000, 0, 2, 2, "INCLUDED"),
                        new StoryContextItem(StoryContextSourceType.USER_INPUT, "11", null, null,
                                "USER", "讨论本章目标", true, 1000, 500, 3, 3, "INCLUDED")),
                List.of(), null);
        when(contextBindingService.buildAndAttach(any(StoryContextBuildCommand.class), any(AiTaskEntity.class)))
                .thenReturn(snapshot);
        org.mockito.Mockito.doAnswer(invocation -> new CompletedCall())
                .when(provider).stream(any(), any());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper, messageMapper, userConfigService, providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher, new LlmStreamCallRegistry(), contextBindingService).run(12L);

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).stream(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().messages()).extracting(message -> message.content())
                .containsExactly("系统规则", "讨论本章目标");
        assertThat(requestCaptor.getValue().options().maxOutputTokens()).isEqualTo(4096);
        org.mockito.Mockito.verify(contextBindingService)
                .buildAndAttach(any(StoryContextBuildCommand.class), any(AiTaskEntity.class));
    }

    private AiTaskEntity task(String status, int version) {
        AiTaskEntity task = new AiTaskEntity();
        task.setId(12L);
        task.setTaskType("conversation_reply");
        task.setTaskStatus(status);
        task.setWorkId(1L);
        task.setChapterId(2L);
        task.setVersion(version);
        task.setDeleted(0);
        return task;
    }

    private ChapterConversationMessageEntity userMessage() {
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setId(11L);
        message.setConversationId(8L);
        message.setChapterId(2L);
        message.setMessageRole("user");
        message.setContent("请讨论本章目标");
        message.setAiTaskId(12L);
        message.setDeleted(0);
        return message;
    }

    private static final class CompletedCall implements LlmStreamCall {

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(LlmStreamStatus.COMPLETED, null, null);
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }

    private static final class CanceledCall implements LlmStreamCall {

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(LlmStreamStatus.CANCELED, null, null);
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }
}
