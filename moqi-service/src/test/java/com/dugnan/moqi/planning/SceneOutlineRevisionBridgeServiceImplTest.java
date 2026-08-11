package com.dugnan.moqi.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateCreated;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.SceneRevisionOutlineCandidateCommand;
import com.dugnan.moqi.chapter.service.OutlineCandidateService;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.CloneScenePlanCandidateRequest;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.CreateOutlineRevisionCandidateRequest;
import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;
import com.dugnan.moqi.planning.entity.ScenePlanConsistencyReportEntity;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanConsistencyReportMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;

/**
 * @author dgn
 * @date 2026-08-12
 * @description 验证场景修订克隆、来源校验、确定性差异和章纲候选桥接。
 */
class SceneOutlineRevisionBridgeServiceImplTest {
    private ChapterMapper chapterMapper;
    private ChapterOutlineQueryMapper outlineMapper;
    private ChapterPlanVersionMapper planMapper;
    private ScenePlanVersionMapper sceneMapper;
    private ScenePlanConsistencyReportMapper reportMapper;
    private OutlineCandidateService outlineCandidateService;
    private SceneOutlineRevisionBridgeServiceImpl service;

    @BeforeEach
    void setUp() {
        chapterMapper = org.mockito.Mockito.mock(ChapterMapper.class);
        outlineMapper = org.mockito.Mockito.mock(ChapterOutlineQueryMapper.class);
        planMapper = org.mockito.Mockito.mock(ChapterPlanVersionMapper.class);
        sceneMapper = org.mockito.Mockito.mock(ScenePlanVersionMapper.class);
        reportMapper = org.mockito.Mockito.mock(ScenePlanConsistencyReportMapper.class);
        outlineCandidateService = org.mockito.Mockito.mock(OutlineCandidateService.class);
        service = new SceneOutlineRevisionBridgeServiceImpl(chapterMapper, outlineMapper, planMapper, sceneMapper,
                reportMapper, outlineCandidateService, new ObjectMapper());
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(65L);
        chapter.setWorkId(17L);
        when(chapterMapper.selectByIdForUpdate(65L)).thenReturn(chapter);
        ChapterOutlineEntity outline = new ChapterOutlineEntity();
        outline.setId(30L);
        outline.setRevision(2);
        when(outlineMapper.findLatest(65L)).thenReturn(outline);
    }

    @Test
    void clonesCurrentPublishedPlanWithoutCreatingAiTask() {
        ChapterPlanVersionEntity source = publishedPlan();
        when(planMapper.selectOne(any())).thenReturn(null);
        when(planMapper.selectById(5L)).thenReturn(source);
        when(planMapper.selectCount(any())).thenReturn(1L);
        when(planMapper.insert(any(ChapterPlanVersionEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChapterPlanVersionEntity.class).setId(9L);
            return 1;
        });
        when(sceneMapper.findAllByPlanId(5L)).thenReturn(List.of(scene(21L, 5L, "scene-1", 1, "旧目标")));

        var result = service.cloneFromCurrent(65L,
                new CloneScenePlanCandidateRequest(5L, 2, "clone-1"));

        assertThat(result.planId()).isEqualTo(9L);
        assertThat(result.sourcePlanId()).isEqualTo(5L);
        assertThat(result.sourcePlanVersion()).isEqualTo(4);
        ArgumentCaptor<ChapterPlanVersionEntity> planCaptor = ArgumentCaptor.forClass(ChapterPlanVersionEntity.class);
        verify(planMapper).insert(planCaptor.capture());
        assertThat(planCaptor.getValue().getAiTaskId()).isNull();
        assertThat(planCaptor.getValue().getPlanStatus()).isEqualTo("ready");
        verify(sceneMapper).insert(any(ScenePlanVersionEntity.class));
        verify(outlineCandidateService, never()).createFromSceneRevision(any(), any());
    }

    @Test
    void reusesClonedDraftForTheSameIdempotentSource() {
        ChapterPlanVersionEntity existing = revisionCandidate();
        existing.setRevisionIdempotencyKey("clone-1");
        when(planMapper.selectOne(any())).thenReturn(existing);

        var result = service.cloneFromCurrent(65L,
                new CloneScenePlanCandidateRequest(5L, 2, "clone-1"));

        assertThat(result.planId()).isEqualTo(9L);
        verify(planMapper, never()).insert(any(ChapterPlanVersionEntity.class));
        verify(sceneMapper, never()).insert(any(ScenePlanVersionEntity.class));
    }

    @Test
    void createsOutlineCandidateWithDeterministicSceneDiffAndReportSource() {
        ChapterPlanVersionEntity source = publishedPlan();
        ChapterPlanVersionEntity candidate = revisionCandidate();
        when(planMapper.selectById(9L)).thenReturn(candidate);
        when(planMapper.selectById(5L)).thenReturn(source);
        ScenePlanConsistencyReportEntity report = new ScenePlanConsistencyReportEntity();
        report.setId(12L);
        report.setChapterId(65L);
        report.setChapterPlanVersionId(9L);
        report.setPlanVersion(3);
        report.setReportStatus("ready");
        report.setDeleted(0);
        when(reportMapper.selectById(12L)).thenReturn(report);
        when(sceneMapper.findAllByPlanId(5L)).thenReturn(List.of(scene(21L, 5L, "scene-1", 1, "旧目标")));
        when(sceneMapper.findAllByPlanId(9L)).thenReturn(List.of(scene(22L, 9L, "scene-1", 2, "新目标")));
        when(outlineCandidateService.createFromSceneRevision(any(), any())).thenReturn(
                new OutlineCandidateCreated(65L, 30L, 2, 44L, 55L, "queued", "adjustment", "outline-1"));

        var result = service.createOutlineCandidate(65L, 9L,
                new CreateOutlineRevisionCandidateRequest(3, 12L, 7L, 8L, 2, "outline-1"));

        assertThat(result.sceneDiff().changes()).singleElement().satisfies(change -> {
            assertThat(change.changeType()).isEqualTo("modified");
            assertThat(change.beforeSequence()).isEqualTo(1);
            assertThat(change.afterSequence()).isEqualTo(2);
            assertThat(change.changedFields()).contains("goal");
        });
        ArgumentCaptor<SceneRevisionOutlineCandidateCommand> commandCaptor =
                ArgumentCaptor.forClass(SceneRevisionOutlineCandidateCommand.class);
        verify(outlineCandidateService).createFromSceneRevision(
                org.mockito.ArgumentMatchers.eq(65L), commandCaptor.capture());
        assertThat(commandCaptor.getValue().sourceScenePlanId()).isEqualTo(9L);
        assertThat(commandCaptor.getValue().sourceConsistencyReportId()).isEqualTo(12L);
        assertThat(commandCaptor.getValue().sceneDiffJson()).contains("scene-1", "goal");
    }

    @Test
    void rejectsReportFromAnotherPlanVersion() {
        when(planMapper.selectById(9L)).thenReturn(revisionCandidate());
        ScenePlanConsistencyReportEntity report = new ScenePlanConsistencyReportEntity();
        report.setId(12L);
        report.setChapterId(65L);
        report.setChapterPlanVersionId(10L);
        report.setPlanVersion(3);
        report.setReportStatus("ready");
        report.setDeleted(0);
        when(reportMapper.selectById(12L)).thenReturn(report);

        assertThatThrownBy(() -> service.createOutlineCandidate(65L, 9L,
                new CreateOutlineRevisionCandidateRequest(3, 12L, 7L, 8L, 2, "outline-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("一致性报告");
    }

    private ChapterPlanVersionEntity publishedPlan() {
        ChapterPlanVersionEntity plan = new ChapterPlanVersionEntity();
        plan.setId(5L);
        plan.setWorkId(17L);
        plan.setChapterId(65L);
        plan.setPlanNo(1);
        plan.setNarrativePlanId(2L);
        plan.setNarrativePlanNo(1);
        plan.setOutlineId(30L);
        plan.setOutlineRevision(2);
        plan.setPlanStatus("published");
        plan.setCurrentMarker(1);
        plan.setDeleted(0);
        plan.setVersion(4);
        return plan;
    }

    private ChapterPlanVersionEntity revisionCandidate() {
        ChapterPlanVersionEntity plan = new ChapterPlanVersionEntity();
        plan.setId(9L);
        plan.setChapterId(65L);
        plan.setOutlineId(30L);
        plan.setOutlineRevision(2);
        plan.setPlanStatus("ready");
        plan.setSourceScenePlanId(5L);
        plan.setSourceScenePlanVersion(4);
        plan.setDeleted(0);
        plan.setVersion(3);
        return plan;
    }

    private ScenePlanVersionEntity scene(Long id, Long planId, String key, Integer sequence, String goal) {
        ScenePlanVersionEntity scene = new ScenePlanVersionEntity();
        scene.setId(id);
        scene.setChapterPlanVersionId(planId);
        scene.setSceneKey(key);
        scene.setSequenceNo(sequence);
        scene.setContentSchemaVersion(2);
        scene.setContentJson("{\"sceneKey\":\"" + key + "\",\"goal\":\"" + goal + "\"}");
        scene.setDeleted(0);
        scene.setVersion(0);
        return scene;
    }
}
