package com.dugnan.moqi.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.context.StoryContextEngine;
import com.dugnan.moqi.context.StoryContextSnapshotQueryPort;
import com.dugnan.moqi.planning.PlanningModels.CreateScenePlanCandidateRequest;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.UpdateScenePlanCandidateRequest;
import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.entity.WorkNarrativePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.planning.mapper.WorkNarrativePlanVersionMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

class StoryPlanningServiceImplTest {

    @Test
    void updatesExistingSceneInPlaceWithoutCollidingWithStableSceneKey() throws Exception {
        ChapterPlanVersionMapper planMapper = mock(ChapterPlanVersionMapper.class);
        ScenePlanVersionMapper sceneMapper = mock(ScenePlanVersionMapper.class);
        ChapterPlanVersionEntity plan = new ChapterPlanVersionEntity();
        plan.setId(10L);
        plan.setChapterId(69L);
        plan.setPlanStatus("ready");
        plan.setVersion(1);
        plan.setDeleted(0);
        ScenePlanContent scene = scene();
        ScenePlanVersionEntity existing = new ScenePlanVersionEntity();
        existing.setId(22L);
        existing.setChapterPlanVersionId(10L);
        existing.setSceneKey(scene.sceneKey());
        existing.setSequenceNo(1);
        existing.setContentSchemaVersion(2);
        existing.setContentJson(new ObjectMapper().writeValueAsString(scene));
        existing.setDeleted(0);
        existing.setVersion(0);
        when(planMapper.selectById(10L)).thenReturn(plan);
        when(planMapper.update(eq(null), any())).thenReturn(1);
        when(sceneMapper.findAllByPlanId(10L)).thenReturn(List.of(existing));
        when(sceneMapper.updateContent(eq(22L), eq(1), eq(2), any(), eq(0))).thenReturn(1);
        when(sceneMapper.selectList(any())).thenReturn(List.of(existing));

        StoryPlanningServiceImpl service = service(planMapper, sceneMapper, new PlanningContentCodec());

        var result = service.updateCandidate(
                69L, 10L, new UpdateScenePlanCandidateRequest(1, null, List.of(scene)));

        assertThat(result.scenes()).hasSize(1);
        verify(sceneMapper).updateContent(eq(22L), eq(1), eq(2), any(), eq(0));
        verify(sceneMapper, never()).insert(any(ScenePlanVersionEntity.class));
    }

    @Test
    void suppliesCurrentInputWhenBuildingScenePlanningContext() {
        WorkMapper workMapper = mock(WorkMapper.class);
        ChapterMapper chapterMapper = mock(ChapterMapper.class);
        ChapterOutlineQueryMapper outlineMapper = mock(ChapterOutlineQueryMapper.class);
        WorkNarrativePlanVersionMapper narrativeMapper = mock(WorkNarrativePlanVersionMapper.class);
        StoryContextEngine storyContextEngine = mock(StoryContextEngine.class);

        WorkEntity work = new WorkEntity();
        work.setId(17L);
        work.setDeleted(0);
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(65L);
        chapter.setWorkId(17L);
        chapter.setDeleted(0);
        WorkNarrativePlanVersionEntity narrative = new WorkNarrativePlanVersionEntity();
        narrative.setId(2L);
        ChapterOutlineEntity outline = new ChapterOutlineEntity();
        outline.setId(30L);
        outline.setRevision(0);

        when(chapterMapper.selectByIdForUpdate(65L)).thenReturn(chapter);
        when(workMapper.selectById(17L)).thenReturn(work);
        when(narrativeMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(narrative);
        when(outlineMapper.findLatest(65L)).thenReturn(outline);
        when(storyContextEngine.build(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            var command = invocation.getArgument(0, com.dugnan.moqi.context.StoryContextBuildCommand.class);
            if (!StringUtils.hasText(command.currentInput())) {
                throw new AssertionError("场景规划上下文必须包含当前用户输入");
            }
            throw new IllegalStateException("上下文契约验证完成");
        });

        StoryPlanningServiceImpl service = new StoryPlanningServiceImpl(
                workMapper, chapterMapper, outlineMapper, narrativeMapper,
                mock(ChapterPlanVersionMapper.class), mock(ScenePlanVersionMapper.class),
                mock(AiTaskMapper.class), mock(AgentRuntime.class), storyContextEngine,
                mock(StoryContextSnapshotQueryPort.class), mock(PlanningContentCodec.class),
                new ObjectMapper());

        assertThatThrownBy(() -> service.createCandidate(
                65L, new CreateScenePlanCandidateRequest(0, "qa-scene-plan")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("上下文契约验证完成");
    }

    private StoryPlanningServiceImpl service(
            ChapterPlanVersionMapper planMapper,
            ScenePlanVersionMapper sceneMapper,
            PlanningContentCodec codec) {
        return new StoryPlanningServiceImpl(
                mock(WorkMapper.class), mock(ChapterMapper.class), mock(ChapterOutlineQueryMapper.class),
                mock(WorkNarrativePlanVersionMapper.class), planMapper, sceneMapper,
                mock(AiTaskMapper.class), mock(AgentRuntime.class), mock(StoryContextEngine.class),
                mock(StoryContextSnapshotQueryPort.class), codec, new ObjectMapper());
    }

    private ScenePlanContent scene() {
        return new ScenePlanContent(
                "scene-001", 1, "Engine Room Recovery", null, "After the alarm", null,
                "Restore control", "Backup power failure", "tense", "fast", List.of(), List.of(), List.of(),
                "Temporary propulsion restored", "planned", List.of("beat-001"),
                List.of("Enemy infiltration is known"), List.of("Backup power failed"),
                "Bridge to engine room", List.of("Control restored"), List.of("Left-arm injury remains"),
                "core", List.of("Alarm details may vary"), List.of("Do not add reinforcements"));
    }
}
