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
import java.util.concurrent.atomic.AtomicInteger;
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
                new ChapterGenerationPromptCompiler(null, null, null, null, null,
                        new com.dugnan.moqi.planning.ScenePlanPromptRenderer()),
                completionHandler, objectMapper);
        lenient().when(provider.capabilities()).thenReturn(
                new LlmProviderCapabilities(true, false, false, 16384, 4096));
        lenient().when(snapshot.id()).thenReturn(21L);
        lenient().when(snapshot.contentHash()).thenReturn("snapshot-hash");
        lenient().when(snapshot.toMessages()).thenReturn(List.of(new LlmMessage(LlmRole.SYSTEM, "上下文")));
        lenient().when(providerFactory.createObserved(any(), any())).thenReturn(provider);
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
    void retriesOnceWithLengthCorrectionAndReturnsOnlyTheCorrectedDraft() throws Exception {
        ChapterGenerationEntity generation = generation();
        ChapterGenerationSceneEntity scene = scene();
        AtomicInteger callNo = new AtomicInteger();
        LlmStreamCall call = completedCall(null);
        when(provider.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta(callNo.getAndIncrement() == 0 ? "短" : "修订正文"));
            return call;
        });

        AgentStepResult result = invoker.generateScene(
                generation, scene, snapshot, new SceneWordRange(3, 4, 5),
                new SceneInvocationContext(executionConfig, provider), context(), "cohere_chapter");

        assertThat(result.outputSummary()).containsEntry("content", "修订正文");
        assertThat(callNo).hasValue(2);
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

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(11L, 12L, "generate_scene:s1", 1, "effect",
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
