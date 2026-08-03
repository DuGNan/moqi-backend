package com.dugnan.moqi.chapter.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterOutlineCandidateEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterOutlineCandidateMapper;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextBuildCommand;
import com.dugnan.moqi.context.StoryContextItem;
import com.dugnan.moqi.context.StoryContextProfile;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextSourceType;
import com.dugnan.moqi.context.StoryContextTaskBindingService;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderCapabilities;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmExecutionConfigDescriptor;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseFormat;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 验证大纲候选任务仅使用 Provider V2 结构化输出并尊重取消竞争。
 */
@ExtendWith(MockitoExtension.class)
class OutlineCandidateTaskRunnerTest {

    @Mock
    private AiTaskMapper taskMapper;
    @Mock
    private ChapterOutlineCandidateMapper candidateMapper;
    @Mock
    private ChapterConversationMapper conversationMapper;
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
    private OutlineCandidatePersistenceService persistenceService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    /**
     * 验证可控 Provider 成功结果以 JSON_OBJECT 交给短事务持久化。
     *
     * @throws Exception JSON 构造失败
     */
    @Test
    void persistsProviderStructuredCandidate() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AiTaskEntity task = task();
        ChapterOutlineCandidateEntity candidate = candidate();
        when(taskMapper.selectById(8L)).thenReturn(task);
        when(candidateMapper.findByTaskId(8L)).thenReturn(candidate);
        when(persistenceService.claim(task, candidate)).thenReturn(true);
        when(conversationMapper.selectById(3L)).thenReturn(conversation());
        when(briefMapper.findByIdAndChapterId(4L, 2L)).thenReturn(brief());
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(executionConfig());
        when(providerFactory.create(any())).thenReturn(provider);
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        when(provider.capabilities()).thenReturn(new LlmProviderCapabilities(true, true, false, 16384, 4096));
        when(contextBindingService.buildAndAttach(any(StoryContextBuildCommand.class), any(AiTaskEntity.class)))
                .thenReturn(snapshot());
        when(provider.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(null, objectMapper.readTree("""
                {"goal":"新目标","coreConflict":"冲突","scenes":[{"id":"scene-1","title":"场景","content":"内容","tags":[]}],"constraints":[]}
                """), null));

        runner(objectMapper).run(8L);

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).generate(requestCaptor.capture());
        assertThat(requestCaptor.getValue().options().responseFormat()).isEqualTo(LlmResponseFormat.JSON_OBJECT);
        verify(persistenceService).complete(any(), any(), any(), any());
    }

    /**
     * 验证取消先获胜后，迟到执行器不会再次调用 Provider 或覆盖候选状态。
     */
    @Test
    void doesNotRunWhenCancellationAlreadyWonClaim() {
        AiTaskEntity task = task();
        ChapterOutlineCandidateEntity candidate = candidate();
        when(taskMapper.selectById(8L)).thenReturn(task);
        when(candidateMapper.findByTaskId(8L)).thenReturn(candidate);
        when(persistenceService.claim(task, candidate)).thenReturn(false);

        runner(new ObjectMapper()).run(8L);

        verify(provider, never()).generate(any());
        verify(persistenceService, never()).complete(any(), any(), any(), any());
        verify(persistenceService, never()).fail(any(), any(), any(), any());
    }

    private OutlineCandidateTaskRunner runner(ObjectMapper objectMapper) {
        return new OutlineCandidateTaskRunner(
                taskMapper, candidateMapper, conversationMapper, briefMapper, userConfigService, providerFactory,
                contextBindingService, persistenceService, objectMapper, eventPublisher);
    }

    private AiTaskEntity task() {
        AiTaskEntity entity = new AiTaskEntity();
        entity.setId(8L);
        entity.setTaskType("outline_adjustment_candidate");
        entity.setTaskStatus("queued");
        entity.setWorkId(1L);
        entity.setChapterId(2L);
        entity.setTaskInputJson("{\"conversationId\":3,\"confirmedBriefId\":4,\"baseOutlineId\":6,\"baseOutlineRevision\":5,\"instruction\":\"强化冲突\"}");
        entity.setDeleted(0);
        entity.setVersion(0);
        return entity;
    }

    private ChapterOutlineCandidateEntity candidate() {
        ChapterOutlineCandidateEntity entity = new ChapterOutlineCandidateEntity();
        entity.setId(7L);
        entity.setChapterId(2L);
        entity.setAiTaskId(8L);
        entity.setBaseOutlineId(6L);
        entity.setBaseOutlineRevision(5);
        entity.setBaseOutlineContent("{\"goal\":\"旧目标\",\"coreConflict\":\"旧冲突\",\"scenes\":[{\"id\":\"scene-1\",\"title\":\"场景\",\"content\":\"内容\",\"tags\":[]}],\"constraints\":[]}");
        entity.setDeleted(0);
        entity.setVersion(0);
        return entity;
    }

    private ChapterConversationEntity conversation() {
        ChapterConversationEntity entity = new ChapterConversationEntity();
        entity.setId(3L);
        entity.setWorkId(1L);
        entity.setChapterId(2L);
        entity.setDeleted(0);
        return entity;
    }

    private ChapterBriefEntity brief() {
        ChapterBriefEntity entity = new ChapterBriefEntity();
        entity.setId(4L);
        entity.setChapterId(2L);
        entity.setBriefStatus("confirmed");
        entity.setBriefContent("已确认共识");
        entity.setDeleted(0);
        return entity;
    }

    private LlmProviderRuntimeConfig config() {
        return new LlmProviderRuntimeConfig("fake", "https://example.test", "test-key", "fake-model");
    }

    private LlmExecutionConfig executionConfig() {
        return new LlmExecutionConfig(
                config(),
                new LlmExecutionConfigDescriptor("fake", "fake-model", 1, 1));
    }

    private StoryContextSnapshot snapshot() {
        return new StoryContextSnapshot(9L, "outline-adjustment:1:2:3", 1L, 2L, 3L,
                StoryContextProfile.OUTLINE_ADJUSTMENT, 1, 1, 16384, 4096, 12288, 0, "hash",
                List.of(new StoryContextItem(StoryContextSourceType.SYSTEM_RULE, "system-v1", null, null,
                        "SYSTEM", "系统规则", true, 1000, 0, 2, 2, "INCLUDED")), List.of(), null);
    }
}
