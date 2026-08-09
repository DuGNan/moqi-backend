package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

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
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmProvider;

/**
 * @author dgn
 * @date 2026-08-09
 * @description 验证章节工作流的步骤顺序、恢复跳过与状态迁移边界。
 */
@ExtendWith(MockitoExtension.class)
class SceneNovelGenerationWorkflowDefinitionTest {

    @Mock
    private ChapterGenerationStateStore stateStore;
    @Mock
    private ChapterGenerationStepPlanner stepPlanner;
    @Mock
    private ChapterGenerationPromptCompiler promptCompiler;
    @Mock
    private ChapterGenerationModelInvoker modelInvoker;
    @Mock
    private ChapterGenerationCompletionHandler completionHandler;

    private ChapterGenerationLengthPolicy lengthPolicy;
    private SceneNovelGenerationWorkflowDefinition workflow;

    @BeforeEach
    void setUp() {
        lengthPolicy = new ChapterGenerationLengthPolicy();
        workflow = new SceneNovelGenerationWorkflowDefinition(
                stateStore, stepPlanner, lengthPolicy, promptCompiler, modelInvoker, completionHandler);
    }

    @Test
    void loadsGenerationAndPlansTheFirstScene() {
        ChapterGenerationEntity generation = generation();
        when(stateStore.requireGeneration(7L)).thenReturn(generation);
        when(stepPlanner.nextStep(7L, 0)).thenReturn("generate_scene:s1");

        AgentStepResult result = workflow.execute("load_generation", context("load_generation"));

        assertThat(result.outputSummary()).containsEntry("generationId", 7L);
        assertThat(result.nextStepKey()).isEqualTo("generate_scene:s1");
    }

    @Test
    void reusesCompletedSceneAndDoesNotInvokeTheModelDuringRecovery() {
        ChapterGenerationEntity generation = generation();
        ChapterGenerationSceneEntity scene = scene("completed");
        when(stateStore.requireGeneration(7L)).thenReturn(generation);
        when(stateStore.requireScene(7L, "s1")).thenReturn(scene);
        when(stepPlanner.nextStep(7L, 1)).thenReturn("cohere_chapter");

        AgentStepResult result = workflow.execute("generate_scene:s1", context("generate_scene:s1"));

        assertThat(result.outputSummary()).containsEntry("skipped", true);
        assertThat(result.nextStepKey()).isEqualTo("cohere_chapter");
        verify(modelInvoker, never()).prepareScene(any());
    }

    @Test
    void delegatesScenePromptAndModelInvocationWithThePlannedNextStep() {
        ChapterGenerationEntity generation = generation();
        ChapterGenerationSceneEntity scene = scene("pending");
        LlmProvider provider = mock(LlmProvider.class);
        SceneInvocationContext invocation = new SceneInvocationContext(mock(LlmExecutionConfig.class), provider);
        StoryContextSnapshot snapshot = mock(StoryContextSnapshot.class);
        AgentStepExecutionContext context = context("generate_scene:s1");
        AgentStepResult expected = AgentStepResult.completed(Map.of("content", "正文"), Map.of(), "cohere_chapter");
        when(stateStore.requireGeneration(7L)).thenReturn(generation);
        when(stateStore.requireScene(7L, "s1")).thenReturn(scene);
        when(stepPlanner.nextStep(7L, 1)).thenReturn("cohere_chapter");
        when(modelInvoker.prepareScene(generation)).thenReturn(invocation);
        when(promptCompiler.compileSnapshot(any(), any(), any(), any())).thenReturn(snapshot);
        when(modelInvoker.generateScene(any(), any(), any(), any(), any(), any(), any())).thenReturn(expected);

        AgentStepResult actual = workflow.execute("generate_scene:s1", context);

        assertThat(actual).isSameAs(expected);
        verify(promptCompiler).compileSnapshot(generation, scene, provider, new SceneWordRange(2700, 3000, 3301));
        verify(modelInvoker).generateScene(
                generation, scene, snapshot, new SceneWordRange(2700, 3000, 3301),
                invocation, context, "cohere_chapter");
    }

    @Test
    void marksCohesionRunningBeforeInvokingTheWholeChapterModel() {
        ChapterGenerationEntity generation = generation();
        List<ChapterGenerationSceneEntity> scenes = List.of(scene("completed"));
        AgentStepResult expected = AgentStepResult.completed(Map.of("content", "整章"), Map.of(), "finalize_generation");
        AgentStepExecutionContext context = context("cohere_chapter");
        when(stateStore.requireGeneration(7L)).thenReturn(generation);
        when(stateStore.completedScenes(7L)).thenReturn(scenes);
        when(modelInvoker.cohereChapter(any(), any(), any(Integer.class), any())).thenReturn(expected);

        AgentStepResult actual = workflow.execute("cohere_chapter", context);

        assertThat(actual).isSameAs(expected);
        verify(stateStore).markCohesionRunning(7L);
        verify(modelInvoker).cohereChapter(generation, scenes, 3000, context);
    }

    @Test
    void persistsAndPublishesEachLifecycleTransition() {
        ChapterGenerationEntity generation = generation();
        ChapterGenerationSceneEntity scene = scene("running");
        AgentStepResult result = AgentStepResult.completed(Map.of("sceneId", 8L, "content", "正文"), Map.of(), null);
        when(stateStore.markStarted(7L)).thenReturn(generation);
        workflow.applyResult("load_generation", context("load_generation"), result);
        verify(completionHandler).generationStarted(generation);

        when(stateStore.applySceneResult(7L, result)).thenReturn(scene);
        when(stateStore.requireGeneration(7L)).thenReturn(generation);
        workflow.applyResult("generate_scene:s1", context("generate_scene:s1"), result);
        verify(completionHandler).sceneCompleted(generation, scene);

        when(stateStore.finalizeGeneration(7L)).thenReturn(generation);
        workflow.applyResult("finalize_generation", context("finalize_generation"), result);
        verify(completionHandler).generationCompleted(generation);
    }

    @Test
    void recordsFailureAgainstTheCurrentStepAndPublishesFailure() {
        ChapterGenerationEntity generation = generation();
        when(stateStore.markFailed(7L, "generate_scene:s1", "generate_scene:", "cohere_chapter"))
                .thenReturn(generation);

        workflow.applyFailure("generate_scene:s1", context("generate_scene:s1"), new IllegalStateException("boom"));

        verify(completionHandler).generationFailed(generation);
    }

    private AgentStepExecutionContext context(String stepKey) {
        return new AgentStepExecutionContext(11L, 12L, stepKey, 1, "effect",
                Map.of("generationId", 7L, "targetChapterWordCount", 3000, "plannedSceneCount", 1),
                Map.of(), Map.of(), mock(AgentRunCallRegistry.class));
    }

    private ChapterGenerationEntity generation() {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(7L);
        generation.setChapterId(6L);
        return generation;
    }

    private ChapterGenerationSceneEntity scene(String status) {
        ChapterGenerationSceneEntity scene = new ChapterGenerationSceneEntity();
        scene.setId(8L);
        scene.setGenerationId(7L);
        scene.setSceneKey("s1");
        scene.setSequenceNo(1);
        scene.setSceneStatus(status);
        return scene;
    }
}
