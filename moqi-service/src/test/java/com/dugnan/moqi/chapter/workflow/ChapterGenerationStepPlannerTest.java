package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;

/**
 * @author dgn
 * @date 2026-08-09
 * @description 验证逐场景执行顺序及历史整章收束步骤选择。
 */
class ChapterGenerationStepPlannerTest {

    @Test
    void selectsNextSceneAndThenKeepsTheHistoricalCohesionStep() {
        ChapterGenerationStateStore stateStore = mock(ChapterGenerationStateStore.class);
        ChapterGenerationStepPlanner planner = new ChapterGenerationStepPlanner(stateStore);
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setContentAssemblyMode("cohesive_chapter");
        ChapterGenerationSceneEntity next = new ChapterGenerationSceneEntity();
        next.setSceneKey("s2");
        when(stateStore.nextScene(7L, 1)).thenReturn(next);
        when(stateStore.nextScene(7L, 2)).thenReturn(null);
        when(stateStore.requireGeneration(7L)).thenReturn(generation);

        assertThat(planner.nextStep(7L, 1)).isEqualTo("generate_scene:s2");
        assertThat(planner.nextStep(7L, 2)).isEqualTo("cohere_chapter");
    }

    @Test
    void selectsOneShotWholeChapterStep() {
        ChapterGenerationStateStore stateStore = mock(ChapterGenerationStateStore.class);
        ChapterGenerationStepPlanner planner = new ChapterGenerationStepPlanner(stateStore);
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setContentAssemblyMode("whole_chapter_once");
        when(stateStore.requireGeneration(7L)).thenReturn(generation);

        assertThat(planner.nextStep(7L, 0)).isEqualTo("generate_chapter");
    }
}
