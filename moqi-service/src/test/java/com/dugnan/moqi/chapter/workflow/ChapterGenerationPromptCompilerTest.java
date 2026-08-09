package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy.SceneWordRange;
import com.dugnan.moqi.context.StoryContextEngine;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextSnapshotQueryPort;
import com.dugnan.moqi.llm.LlmProvider;
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
                scenePlanMapper, contextEngine, snapshotQueryPort, stateStore, new ObjectMapper());
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
}
