package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;

/**
 * @author dgn
 * @date 2026-08-09
 * @description 验证章节生成候选保存、恢复幂等和历史拼接的持久化边界。
 */
@ExtendWith(MockitoExtension.class)
class ChapterGenerationStateStoreTest {

    @Mock
    private ChapterGenerationMapper generationMapper;
    @Mock
    private ChapterGenerationSceneMapper sceneMapper;

    private ChapterGenerationStateStore stateStore;

    @BeforeEach
    void setUp() {
        stateStore = new ChapterGenerationStateStore(generationMapper, sceneMapper);
    }

    @Test
    void savesGeneratedSceneCandidateWithOptimisticStatusCondition() {
        ChapterGenerationSceneEntity scene = scene("running");
        when(sceneMapper.selectById(8L)).thenReturn(scene);
        when(sceneMapper.update(isNull(), any())).thenReturn(1);
        AgentStepResult result = AgentStepResult.completed(Map.of(
                "sceneId", 8L,
                "content", "候选正文",
                "modelCallId", 31L,
                "finishReason", "stop",
                "inputTokens", 10,
                "outputTokens", 20,
                "totalTokens", 30,
                "elapsedMillis", 40L), Map.of(), null);

        ChapterGenerationSceneEntity saved = stateStore.applySceneResult(7L, result);

        assertThat(saved).isSameAs(scene);
        ArgumentCaptor<UpdateWrapper<ChapterGenerationSceneEntity>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(sceneMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet())
                .contains("generated_content", "content_hash", "model_call_id", "scene_status", "version");
    }

    @Test
    void treatsAnAlreadyCompletedSceneAsAnIdempotentRecovery() {
        ChapterGenerationSceneEntity scene = scene("completed");
        when(sceneMapper.selectById(8L)).thenReturn(scene);
        AgentStepResult result = AgentStepResult.completed(
                Map.of("sceneId", 8L, "content", "候选正文"), Map.of(), null);

        assertThat(stateStore.applySceneResult(7L, result)).isNull();
        verify(sceneMapper, never()).update(isNull(), any());
    }

    @Test
    void finalizesLegacyAssemblyFromCompletedSceneCandidates() {
        ChapterGenerationEntity generation = generation("scene_join_legacy", null);
        when(generationMapper.selectById(7L)).thenReturn(generation);
        when(sceneMapper.selectList(any())).thenReturn(List.of(scene("completed")));
        when(generationMapper.update(isNull(), any())).thenReturn(1);

        ChapterGenerationEntity finalized = stateStore.finalizeGeneration(7L);

        assertThat(finalized).isSameAs(generation);
        ArgumentCaptor<UpdateWrapper<ChapterGenerationEntity>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(generationMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet())
                .contains("generated_content", "word_count", "generation_status", "version");
    }

    @Test
    void persistsWholeChapterCandidateAndFinalizesWithoutSceneRows() {
        ChapterGenerationEntity generation = generation("whole_chapter_once", "not_applicable");
        generation.setGeneratedContent("整章候选正文");
        when(generationMapper.selectById(7L)).thenReturn(generation);
        when(generationMapper.update(isNull(), any())).thenReturn(1);
        AgentStepResult result = AgentStepResult.completed(Map.of(
                "content", "整章候选正文",
                "modelCallId", 41L,
                "templateVersion", "whole-chapter-v1",
                "finishReason", "stop"), Map.of(), "finalize_generation");

        stateStore.applyWholeChapterResult(7L, result);
        ChapterGenerationEntity finalized = stateStore.finalizeGeneration(7L);

        assertThat(finalized).isSameAs(generation);
        verify(sceneMapper, never()).selectList(any());
        ArgumentCaptor<UpdateWrapper<ChapterGenerationEntity>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(generationMapper, org.mockito.Mockito.times(2)).update(isNull(), captor.capture());
        assertThat(captor.getAllValues().get(0).getSqlSet())
                .contains("generation_model_call_id", "generation_template_version", "generation_finish_reason");
        assertThat(captor.getAllValues().get(1).getSqlSet()).contains("generation_status", "version");
    }

    private ChapterGenerationEntity generation(String assemblyMode, String cohesionStatus) {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(7L);
        generation.setVersion(2);
        generation.setGenerationStatus("running");
        generation.setContentAssemblyMode(assemblyMode);
        generation.setCohesionStatus(cohesionStatus);
        return generation;
    }

    private ChapterGenerationSceneEntity scene(String status) {
        ChapterGenerationSceneEntity scene = new ChapterGenerationSceneEntity();
        scene.setId(8L);
        scene.setGenerationId(7L);
        scene.setSceneKey("s1");
        scene.setSequenceNo(1);
        scene.setSceneStatus(status);
        scene.setGeneratedContent("第一场正文");
        scene.setVersion(3);
        return scene;
    }
}
