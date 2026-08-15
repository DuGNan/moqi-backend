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

/**
 * @author dgn
 * @date 2026-08-09
 * @description 验证恢复与重试复用来源快照且不重新选择故事事实。
 */
class ChapterGenerationPromptCompilerTest {

    @Test
    void reusesPersistedSnapshotWithoutRebuildingOrMutatingSceneState() {
        StoryContextEngine contextEngine = mock(StoryContextEngine.class);
        StoryContextSnapshotQueryPort snapshotQueryPort = mock(StoryContextSnapshotQueryPort.class);
        ChapterGenerationStateStore stateStore = mock(ChapterGenerationStateStore.class);
        ChapterGenerationPromptCompiler compiler = new ChapterGenerationPromptCompiler(
                contextEngine, snapshotQueryPort, stateStore, new ObjectMapper());
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
    }

    @Test
    void injectsFrozenHumanReadableBriefWithoutReReadingScenePlans() {
        StoryContextEngine contextEngine = mock(StoryContextEngine.class);
        StoryContextSnapshotQueryPort snapshotQueryPort = mock(StoryContextSnapshotQueryPort.class);
        ChapterGenerationStateStore stateStore = mock(ChapterGenerationStateStore.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChapterGenerationPromptCompiler compiler = new ChapterGenerationPromptCompiler(
                contextEngine, snapshotQueryPort, stateStore, objectMapper);
        ChapterGenerationEntity generation = generation();
        ChapterGenerationSceneEntity current = generationScene(8L, 101L, "alarm", 1);
        when(stateStore.previousCompletedScenes(7L, 1)).thenReturn(List.of());
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.capabilities()).thenReturn(new LlmProviderCapabilities(true, true, false, 16384, 4096));
        StoryContextSnapshot snapshot = mock(StoryContextSnapshot.class);
        when(contextEngine.build(org.mockito.ArgumentMatchers.any())).thenReturn(snapshot);

        compiler.compileSnapshot(generation, current, provider, new SceneWordRange(90, 100, 110));

        org.mockito.ArgumentCaptor<StoryContextBuildCommand> commandCaptor =
                org.mockito.ArgumentCaptor.forClass(StoryContextBuildCommand.class);
        verify(contextEngine).build(commandCaptor.capture());
        var focus = commandCaptor.getValue().sceneGenerationFocus();
        assertThat(focus.generationBriefContent()).contains("# Chapter Generation Brief", "因果前置")
                .doesNotContain("\"sceneKey\"", "{");
        assertThat(focus.briefFingerprint()).isEqualTo("brief-hash");
        assertThat(commandCaptor.getValue().currentInput()).contains("当前只创作场景 alarm");
    }

    @Test
    void compilesStableHumanReadableWholeChapterPromptFromFrozenInputs() {
        ChapterGenerationPromptCompiler compiler = new ChapterGenerationPromptCompiler(
                null, null, null, new ObjectMapper());
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setBasisSnapshotJson("""
                {
                  "chapterGenerationBrief": {
                    "templateVersion": "chapter-generation-brief-v2-entity-cards",
                    "fingerprint": "brief-hash",
                    "content": "# Chapter Generation Brief\\n## 事件因果\\n- 因果前置\\n## 相关实体说明\\n- 林风：禁止推断身世"
                  },
                  "chapterCapacityAssessment": {
                    "inputFingerprint": "capacity-hash",
                    "result": {
                      "status": "fits",
                      "suggestedMinimumWordCount": 2600,
                      "suggestedMaximumWordCount": 3400,
                      "reasons": ["事件密度适中"],
                      "eventWeights": [{"eventKey":"scene-1","weight":"high","reason":"核心转折"}],
                      "nonCompressibleCausalNodes": ["必须发现密室"]
                    }
                  },
                  "baseGeneration": {
                    "contentHash": "base-hash",
                    "content": "林风推开了门。"
                  }
                }
                """);

        var first = compiler.compileWholeChapter(generation, 3000);
        var second = compiler.compileWholeChapter(generation, 3000);

        assertThat(first.templateVersion()).isEqualTo("whole-chapter-v1");
        assertThat(first.sourceFingerprint()).isEqualTo(second.sourceFingerprint());
        assertThat(first.messages()).extracting(message -> message.content()).allSatisfy(content ->
                assertThat(content).doesNotContain("\"sceneKey\"", "{\""));
        assertThat(first.messages().get(1).content())
                .contains("因果前置", "林风：禁止推断身世", "建议区间：2600 至 3400 字",
                        "scene-1：high（核心转折）", "林风推开了门。")
                .doesNotContain("ScenePlan");
    }

    private ChapterGenerationEntity generation() {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(7L);
        generation.setWorkId(17L);
        generation.setChapterId(65L);
        generation.setChapterPlanVersionId(31L);
        generation.setBasisSnapshotJson("""
                {"chapterGenerationBrief":{"templateVersion":"chapter-generation-brief-v1",
                "fingerprint":"brief-hash","content":"# Chapter Generation Brief\\n- 因果前置"}}
                """);
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

}
