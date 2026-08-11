package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy.SceneWordRange;
import com.dugnan.moqi.context.StoryContextEngine;
import com.dugnan.moqi.context.StoryContextBuildCommand;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextSnapshotQueryPort;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderCapabilities;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.ScenePlanPromptRenderer;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;

/**
 * @author dgn
 * @date 2026-08-09
 * @description 验证恢复与重试复用来源快照且不重新选择故事事实。
 */
class ChapterGenerationPromptCompilerTest {

    @Test
    void reusesPersistedSnapshotWithoutRebuildingOrMutatingSceneState() {
        ScenePlanVersionMapper scenePlanMapper = mock(ScenePlanVersionMapper.class);
        StoryContextEngine contextEngine = mock(StoryContextEngine.class);
        StoryContextSnapshotQueryPort snapshotQueryPort = mock(StoryContextSnapshotQueryPort.class);
        ChapterGenerationStateStore stateStore = mock(ChapterGenerationStateStore.class);
        ChapterGenerationPromptCompiler compiler = new ChapterGenerationPromptCompiler(
                scenePlanMapper, contextEngine, snapshotQueryPort, stateStore, new ObjectMapper(),
                new ScenePlanPromptRenderer());
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(7L);
        ChapterGenerationSceneEntity scene = new ChapterGenerationSceneEntity();
        scene.setId(8L);
        scene.setContextSnapshotId(21L);
        StoryContextSnapshot expected = mock(StoryContextSnapshot.class);
        when(snapshotQueryPort.load(21L)).thenReturn(expected);

        StoryContextSnapshot actual = compiler.compileSnapshot(
                generation, scene, mock(LlmProvider.class), new SceneWordRange(90, 100, 110));

        assertThat(actual).isSameAs(expected);
        verify(contextEngine, never()).build(org.mockito.ArgumentMatchers.any());
        verify(stateStore, never()).markSceneRunning(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(scenePlanMapper, never()).selectById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rendersCurrentAdjacentAndRoutePlansAsNaturalLanguage() throws Exception {
        ScenePlanVersionMapper scenePlanMapper = mock(ScenePlanVersionMapper.class);
        StoryContextEngine contextEngine = mock(StoryContextEngine.class);
        StoryContextSnapshotQueryPort snapshotQueryPort = mock(StoryContextSnapshotQueryPort.class);
        ChapterGenerationStateStore stateStore = mock(ChapterGenerationStateStore.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChapterGenerationPromptCompiler compiler = new ChapterGenerationPromptCompiler(
                scenePlanMapper, contextEngine, snapshotQueryPort, stateStore, objectMapper,
                new ScenePlanPromptRenderer());
        ChapterGenerationEntity generation = generation();
        ChapterGenerationSceneEntity current = generationScene(8L, 101L, "alarm", 1);
        ChapterGenerationSceneEntity next = generationScene(9L, 102L, "escape", 2);
        when(scenePlanMapper.selectById(101L)).thenReturn(planEntity(101L, scene("alarm", 1), objectMapper));
        when(scenePlanMapper.selectById(102L)).thenReturn(planEntity(102L, scene("escape", 2), objectMapper));
        when(stateStore.previousCompletedScenes(7L, 1)).thenReturn(List.of());
        when(stateStore.nextScene(7L, 1)).thenReturn(next);
        when(stateStore.scenes(7L)).thenReturn(List.of(current, next));
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.capabilities()).thenReturn(new LlmProviderCapabilities(true, true, false, 16384, 4096));
        StoryContextSnapshot snapshot = mock(StoryContextSnapshot.class);
        when(contextEngine.build(org.mockito.ArgumentMatchers.any())).thenReturn(snapshot);

        compiler.compileSnapshot(generation, current, provider, new SceneWordRange(90, 100, 110));

        org.mockito.ArgumentCaptor<StoryContextBuildCommand> commandCaptor =
                org.mockito.ArgumentCaptor.forClass(StoryContextBuildCommand.class);
        verify(contextEngine).build(commandCaptor.capture());
        var focus = commandCaptor.getValue().sceneGenerationFocus();
        assertThat(focus.sceneContent()).contains("场景 1｜alarm", "因果前置：备用电源失效")
                .doesNotContain("\"sceneKey\"", "{");
        assertThat(focus.nextSceneContent()).contains("场景 2｜escape").doesNotContain("{");
        assertThat(focus.chapterSceneRoute()).contains("场景 1｜alarm", "场景 2｜escape")
                .doesNotContain("\"sceneKey\"", "{");
    }

    private ChapterGenerationEntity generation() {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(7L);
        generation.setWorkId(17L);
        generation.setChapterId(65L);
        generation.setChapterPlanVersionId(31L);
        return generation;
    }

    private ChapterGenerationSceneEntity generationScene(Long id, Long planId, String key, int sequence) {
        ChapterGenerationSceneEntity scene = new ChapterGenerationSceneEntity();
        scene.setId(id);
        scene.setScenePlanVersionId(planId);
        scene.setSceneKey(key);
        scene.setSequenceNo(sequence);
        return scene;
    }

    private ScenePlanVersionEntity planEntity(Long id, ScenePlanContent content, ObjectMapper objectMapper)
            throws Exception {
        ScenePlanVersionEntity entity = new ScenePlanVersionEntity();
        entity.setId(id);
        entity.setVersion(0);
        entity.setDeleted(0);
        entity.setContentJson(objectMapper.writeValueAsString(content));
        return entity;
    }

    private ScenePlanContent scene(String key, int sequence) {
        return new ScenePlanContent(
                key, sequence, key, null, "深夜", null, "脱险", "舱门锁死", "紧张", "快速",
                List.of(), List.of(), List.of(), "抵达安全区", "planned", List.of("beat-1"),
                List.of("敌方已经潜入"), List.of("备用电源失效"), "从舰桥进入机舱",
                List.of("主角取得控制权"), List.of("左臂仍有伤"), "core", List.of(),
                List.of("不得新增援军"));
    }
}
