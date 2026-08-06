package com.dugnan.moqi.planning;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.context.StoryContextEngine;
import com.dugnan.moqi.context.StoryContextSnapshotQueryPort;
import com.dugnan.moqi.planning.PlanningModels.CreateScenePlanCandidateRequest;
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
}
