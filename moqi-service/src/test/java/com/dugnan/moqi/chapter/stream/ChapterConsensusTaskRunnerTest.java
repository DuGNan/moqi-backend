package com.dugnan.moqi.chapter.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextBuildCommand;
import com.dugnan.moqi.context.StoryContextProfile;
import com.dugnan.moqi.context.StoryContextItem;
import com.dugnan.moqi.context.StoryContextSourceType;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextTaskBindingService;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderCapabilities;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseFormat;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 验证共识任务通过 Context Snapshot 调用 Provider V2 结构化输出。
 */
@ExtendWith(MockitoExtension.class)
class ChapterConsensusTaskRunnerTest {

    @Mock
    private AiTaskMapper taskMapper;
    @Mock
    private ChapterConversationMapper conversationMapper;
    @Mock
    private ChapterConversationMessageMapper messageMapper;
    @Mock
    private ChapterBriefMapper briefMapper;
    @Mock
    private UserConfigService userConfigService;
    @Mock
    private LlmProviderFactory providerFactory;
    @Mock
    private LlmProvider provider;
    @Mock
    private StoryContextTaskBindingService contextBindingService;
    @Mock
    private ChapterConsensusPersistenceService persistenceService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    /**
     * 验证成功任务只消费 structuredContent 并发布安全 Brief 事件。
     */
    @Test
    void generatesStructuredDraftAndPublishesResourceEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AiTaskEntity task = task();
        when(taskMapper.selectById(31L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(8L)).thenReturn(conversation());
        when(messageMapper.selectById(11L)).thenReturn(message());
        when(briefMapper.findByIdAndChapterId(21L, 2L)).thenReturn(brief());
        when(userConfigService.requireAvailableModelConfig())
                .thenReturn(new LlmProviderRuntimeConfig(
                        "deepseek",
                        "https://api.deepseek.com",
                        "test-key",
                        "deepseek-v4-flash"));
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.capabilities()).thenReturn(
                new LlmProviderCapabilities(true, true, false, 16384, 4096));
        when(contextBindingService.buildAndAttach(any(StoryContextBuildCommand.class), any(AiTaskEntity.class)))
                .thenReturn(snapshot());
        when(provider.generate(any())).thenReturn(new LlmResponse(
                null,
                objectMapper.readTree("""
                        {
                          "schemaVersion": 1,
                          "chapterTask": "推进选择",
                          "stateChange": {"from": "犹豫", "to": "决断"},
                          "keyPush": "承担代价",
                          "readerProgress": {"payoff": "兑现", "openQuestion": "谁泄密"},
                          "writingBoundaries": [],
                          "decisions": []
                        }
                        """),
                null));
        when(persistenceService.complete(any(), any(), any(ChapterConsensusContentV1.class)))
                .thenReturn(41L);

        runner(objectMapper).run(31L);

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).generate(requestCaptor.capture());
        assertThat(requestCaptor.getValue().options().responseFormat())
                .isEqualTo(LlmResponseFormat.JSON_OBJECT);
        verify(eventPublisher).publishEvent(ChapterBriefEvent.draftUpdated(2L, 31L, 41L));
    }

    private ChapterConsensusTaskRunner runner(ObjectMapper objectMapper) {
        return new ChapterConsensusTaskRunner(
                taskMapper,
                conversationMapper,
                messageMapper,
                briefMapper,
                userConfigService,
                providerFactory,
                contextBindingService,
                persistenceService,
                objectMapper,
                eventPublisher);
    }

    private AiTaskEntity task() {
        AiTaskEntity task = new AiTaskEntity();
        task.setId(31L);
        task.setTaskType("chapter_consensus");
        task.setTaskStatus("queued");
        task.setWorkId(1L);
        task.setChapterId(2L);
        task.setTaskInputJson(
                "{\"conversationId\":8,\"baseBriefId\":21,\"currentMessageId\":11}");
        task.setVersion(0);
        task.setDeleted(0);
        return task;
    }

    private ChapterConversationEntity conversation() {
        ChapterConversationEntity conversation = new ChapterConversationEntity();
        conversation.setId(8L);
        conversation.setWorkId(1L);
        conversation.setChapterId(2L);
        conversation.setDeleted(0);
        return conversation;
    }

    private ChapterConversationMessageEntity message() {
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setId(11L);
        message.setConversationId(8L);
        message.setChapterId(2L);
        message.setMessageRole("user");
        message.setContent("请收束本章共识");
        message.setDeleted(0);
        return message;
    }

    private ChapterBriefEntity brief() {
        ChapterBriefEntity brief = new ChapterBriefEntity();
        brief.setId(21L);
        brief.setChapterId(2L);
        brief.setBriefContent("{\"schemaVersion\":1}");
        brief.setDeleted(0);
        return brief;
    }

    private StoryContextSnapshot snapshot() {
        return new StoryContextSnapshot(
                51L,
                "chapter_discussion:1:2:8",
                1L,
                2L,
                8L,
                StoryContextProfile.CHAPTER_DISCUSSION,
                1,
                1,
                16384,
                4096,
                12288,
                0,
                "hash",
                List.of(new StoryContextItem(
                        StoryContextSourceType.SYSTEM_RULE,
                        "system-v1",
                        null,
                        null,
                        "SYSTEM",
                        "系统规则",
                        true,
                        1000,
                        0,
                        2,
                        2,
                        "INCLUDED")),
                List.of(),
                null);
    }
}
