package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.agent.AgentRunCallRegistry;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy.SceneWordRange;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationModelInvoker.SceneInvocationContext;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationPromptCompiler.WholeChapterPrompt;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmExecutionConfigDescriptor;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderCapabilities;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;
import com.dugnan.moqi.llm.LlmResponseMetadata;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmRole;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;

/**
 * @author dgn
 * @date 2026-08-09
 * @description 使用 Fake Provider 验证模型观测、长度校正、取消与调用标识行为。
 */
@ExtendWith(MockitoExtension.class)
class ChapterGenerationModelInvokerTest {

    @Mock
    private UserConfigService userConfigService;
    @Mock
    private LlmProviderFactory providerFactory;
    @Mock
    private ChapterGenerationCompletionHandler completionHandler;
    @Mock
    private LlmProvider provider;
    @Mock
    private StoryContextSnapshot snapshot;

    private ObjectMapper objectMapper;
    private ChapterGenerationModelInvoker invoker;
    private LlmExecutionConfig executionConfig;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        executionConfig = new LlmExecutionConfig(
                new LlmProviderRuntimeConfig("fake", "http://fake", "secret", "fake-model"),
                new LlmExecutionConfigDescriptor("fake", "fake-model", 1, 1));
        invoker = new ChapterGenerationModelInvoker(
                userConfigService, providerFactory, new ChapterGenerationLengthPolicy(),
                new ChapterGenerationPromptCompiler(null, null, null, null),
                completionHandler, objectMapper);
        lenient().when(provider.capabilities()).thenReturn(
                new LlmProviderCapabilities(true, false, false, 16384, 4096));
        lenient().when(snapshot.id()).thenReturn(21L);
        lenient().when(snapshot.contentHash()).thenReturn("snapshot-hash");
        lenient().when(snapshot.toMessages()).thenReturn(List.of(new LlmMessage(LlmRole.SYSTEM, "上下文")));
        lenient().when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        lenient().when(userConfigService.requireAvailableExecutionConfig()).thenReturn(executionConfig);
    }

    @Test
    void emitsStreamedSceneAndKeepsObservedModelCallMetadata() throws Exception {
        ChapterGenerationEntity generation = generation();
        ChapterGenerationSceneEntity scene = scene();
        LlmResponseMetadata metadata = new LlmResponseMetadata(
                "fake", "fake-model", "stop", 10, 2, 12, "request-1", 31L);
        LlmStreamCall call = completedCall(metadata);
        when(provider.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("正文"));
            return call;
        });

        AgentStepResult result = invoker.generateScene(
                generation, scene, snapshot, new SceneWordRange(1, 2, 3),
                new SceneInvocationContext(executionConfig, provider), context(), "cohere_chapter");

        assertThat(result.outputSummary())
                .containsEntry("content", "正文")
                .containsEntry("modelCallId", 31L)
                .containsEntry("finishReason", "stop");
        assertThat(result.modelCallRef()).isEqualTo("31");
        verify(completionHandler).sceneStarted(generation, scene);
        verify(completionHandler).sceneDelta(generation, scene, "正文");
    }

    @Test
    void keepsACompleteDraftOutsideTheSoftRangeWithoutAutomaticLengthCorrection() throws Exception {
        ChapterGenerationEntity generation = generation();
        ChapterGenerationSceneEntity scene = scene();
        LlmStreamCall call = completedCall(null);
        when(provider.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("短"));
            return call;
        });

        AgentStepResult result = invoker.generateScene(
                generation, scene, snapshot, new SceneWordRange(3, 4, 5),
                new SceneInvocationContext(executionConfig, provider), context(), "cohere_chapter");

        assertThat(result.outputSummary()).containsEntry("content", "短");
        verify(provider).stream(any(), any());
    }

    @Test
    void mapsCanceledProviderCallToGenerationConflict() throws Exception {
        ChapterGenerationEntity generation = generation();
        ChapterGenerationSceneEntity scene = scene();
        LlmStreamCall call = mock(LlmStreamCall.class);
        when(call.await()).thenReturn(new LlmStreamResult(LlmStreamStatus.CANCELED, null, null));
        when(provider.stream(any(), any())).thenReturn(call);

        assertThatThrownBy(() -> invoker.generateScene(
                generation, scene, snapshot, new SceneWordRange(1, 2, 3),
                new SceneInvocationContext(executionConfig, provider), context(), "cohere_chapter"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已取消");
    }

    @Test
    void rejectsAStalePersistedExecutionConfigurationBeforeCreatingProvider() throws Exception {
        ChapterGenerationEntity generation = generation();
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(new LlmExecutionConfig(
                executionConfig.runtimeConfig(), new LlmExecutionConfigDescriptor("fake", "other", 2, 1)));

        assertThatThrownBy(() -> invoker.prepareScene(generation))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模型配置或凭据已变化");
    }

    @Test
    void streamsOneShotWholeChapterAndKeepsModelMetadata() throws Exception {
        ChapterGenerationEntity generation = generation();
        LlmResponseMetadata metadata = new LlmResponseMetadata(
                "fake", "fake-model", "stop", 100, 800, 900, "request-whole", 41L);
        LlmStreamCall call = completedCall(metadata);
        when(provider.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("夜雨落在窗沿。"));
            return call;
        });
        WholeChapterPrompt prompt = new WholeChapterPrompt(
                List.of(new LlmMessage(LlmRole.SYSTEM, "只输出正文")), "source-hash", "whole-chapter-v1");

        AgentStepResult result = invoker.generateWholeChapter(generation, prompt, 3000, nullSafeContext());

        assertThat(result.outputSummary())
                .containsEntry("content", "夜雨落在窗沿。")
                .containsEntry("modelCallId", 41L)
                .containsEntry("finishReason", "stop")
                .containsEntry("templateVersion", "whole-chapter-v1");
        verify(completionHandler).generationDelta(generation, "夜雨落在窗沿。");
    }

    @Test
    void rejectsJsonOrPromptEchoAsWholeChapterProse() throws Exception {
        ChapterGenerationEntity generation = generation();
        LlmStreamCall call = completedCall(null);
        when(provider.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("{\"content\":\"正文\"}"));
            return call;
        });
        WholeChapterPrompt prompt = new WholeChapterPrompt(
                List.of(new LlmMessage(LlmRole.SYSTEM, "只输出正文")), "source-hash", "whole-chapter-v1");

        assertThatThrownBy(() -> invoker.generateWholeChapter(generation, prompt, 3000, nullSafeContext()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是纯小说正文");
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(11L, 12L, "generate_scene:s1", 1, "effect",
                Map.of("generationId", 7L), Map.of(), Map.of(), new AgentRunCallRegistry());
    }

    private AgentStepExecutionContext nullSafeContext() {
        return new AgentStepExecutionContext(11L, 12L, "generate_chapter", 1, "effect",
                Map.of("generationId", 7L), Map.of(), Map.of(), new AgentRunCallRegistry());
    }

    private ChapterGenerationEntity generation() throws Exception {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(7L);
        generation.setWorkId(5L);
        generation.setChapterId(6L);
        generation.setExecutionConfigJson(objectMapper.writeValueAsString(executionConfig.descriptor()));
        return generation;
    }

    private ChapterGenerationSceneEntity scene() {
        ChapterGenerationSceneEntity scene = new ChapterGenerationSceneEntity();
        scene.setId(8L);
        scene.setGenerationId(7L);
        scene.setSceneKey("s1");
        return scene;
    }

    private LlmStreamCall completedCall(LlmResponseMetadata metadata) {
        LlmStreamCall call = mock(LlmStreamCall.class);
        when(call.await()).thenReturn(new LlmStreamResult(LlmStreamStatus.COMPLETED, metadata, null));
        return call;
    }
}
