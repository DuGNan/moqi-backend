package com.dugnan.moqi.chapter.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;
import com.dugnan.moqi.chapter.entity.ProsePlanningChangePackageEntity;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ProsePlanningChangePackageMapper;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ModelPlanningProposal;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.PlanningContext;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.PlanningContentCodec;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 验证规划变更包来源冻结、双 CAS 发布与冲突回滚前置条件。
 */
class ProsePlanningChangeServiceImplTest {

    @Test
    void freezesCurrentPlanningContextForSameModelCall() {
        Fixture fixture = new Fixture();

        PlanningContext context = fixture.service.freezeContext(2L);

        assertThat(context.baseOutlineId()).isEqualTo(10L);
        assertThat(context.baseScenePlanId()).isEqualTo(20L);
        assertThat(context.beforeSummary()).contains("第一场");
        assertThat(context.scenes()).hasSize(1);
    }

    @Test
    void createsAndAppliesPlanningPackageAsNewPublishedVersion() {
        Fixture fixture = new Fixture();
        var created = fixture.service.createCandidate(9L, fixture.proposal());

        Long resultPlanId = fixture.service.apply(
                2L, fixture.candidate, created.id(), 5, "new-hash", List.of(9L));

        assertThat(resultPlanId).isEqualTo(21L);
        verify(fixture.planMapper).supersedeCurrentIfVersion(20L, 4);
        verify(fixture.planMapper).insert(any(ChapterPlanVersionEntity.class));
        verify(fixture.sceneMapper).insert(any(ScenePlanVersionEntity.class));
        verify(fixture.packageMapper).markApplied(30L, 5, "new-hash", 21L, 0);
    }

    @Test
    void rejectsStaleOutlineBeforeChangingCandidateOrPlan() {
        Fixture fixture = new Fixture();
        fixture.service.createCandidate(9L, fixture.proposal());
        fixture.outline.setVersion(4);

        assertThatThrownBy(() -> fixture.service.apply(
                2L, fixture.candidate, 30L, 5, "new-hash", List.of(9L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("来源已过期");

        verify(fixture.planMapper, never()).supersedeCurrentIfVersion(any(), any());
        verify(fixture.planMapper, never()).insert(any(ChapterPlanVersionEntity.class));
        verify(fixture.packageMapper, never()).markApplied(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsModelPlanningProposalWhenFrozenSourceExpiredWithoutCreatingPackage() {
        Fixture fixture = new Fixture();
        fixture.outline.setVersion(4);

        assertThatThrownBy(() -> fixture.service.createCandidate(9L, fixture.proposal()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("来源已过期");

        verify(fixture.packageMapper, never()).insert(any(ProsePlanningChangePackageEntity.class));
        verify(fixture.planMapper, never()).supersedeCurrentIfVersion(any(), any());
    }

    @Test
    void planningApplyRequiresItsSourceProposalToBeExplicitlyApplied() {
        Fixture fixture = new Fixture();
        fixture.service.createCandidate(9L, fixture.proposal());

        assertThatThrownBy(() -> fixture.service.apply(
                2L, fixture.candidate, 30L, 5, "new-hash", List.of(10L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同时结算");

        verify(fixture.planMapper, never()).supersedeCurrentIfVersion(any(), any());
    }

    @Test
    void rejectsDifferentIdempotencyKeyForSameAssistanceAsBusinessConflict() {
        Fixture fixture = new Fixture();
        fixture.service.createCandidate(9L, fixture.proposal());
        when(fixture.packageMapper.selectOne(any())).thenReturn(fixture.changePackage);

        assertThatThrownBy(() -> fixture.service.createCandidate(9L,
                new ModelPlanningProposal("另一原因", "共 1 场", "另一摘要", List.of(Fixture.scene()))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已经绑定不同");

        verify(fixture.packageMapper).insert(any(ProsePlanningChangePackageEntity.class));
    }

    @Test
    void replaysSamePlanningPackageAfterJsonStorageNormalizationWithoutSecondInsert() throws Exception {
        Fixture fixture = new Fixture();
        var first = fixture.service.createCandidate(9L, fixture.proposal());
        fixture.changePackage.setProposedScenesJson(
                fixture.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(List.of(Fixture.scene())));
        when(fixture.packageMapper.selectOne(any())).thenReturn(fixture.changePackage);

        var replay = fixture.service.createCandidate(9L, fixture.proposal());

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.targetObjectId()).isEqualTo("candidate:8");
        verify(fixture.packageMapper, times(1)).insert(any(ProsePlanningChangePackageEntity.class));
    }

    @Test
    void rejectsPackageWhenCandidateChangedBeforeAtomicSave() {
        Fixture fixture = new Fixture();
        fixture.service.createCandidate(9L, fixture.proposal());
        fixture.candidate.setVersion(5);
        fixture.candidate.setContentHash("changed-hash");

        assertThatThrownBy(() -> fixture.service.apply(
                2L, fixture.candidate, 30L, 6, "saved-hash", List.of(9L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正文候选已在规划确认后变化");

        verify(fixture.planMapper, never()).supersedeCurrentIfVersion(any(), any());
        verify(fixture.planMapper, never()).insert(any(ChapterPlanVersionEntity.class));
        verify(fixture.packageMapper, never()).markApplied(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAssistanceWithoutCandidateAsSafeBusinessConflict() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity unbound = Fixture.assistance();
        unbound.setTargetCandidateId(null);
        unbound.setPlanningContextJson(fixture.json(new PlanningContext(
                10L, 2, 3, 20L, 4, "共 1 场", List.of(Fixture.scene()))));
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(unbound);

        assertThatThrownBy(() -> fixture.service.createCandidate(9L, fixture.proposal()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未绑定稳定候选");

        verify(fixture.packageMapper, never()).insert(any(ProsePlanningChangePackageEntity.class));
    }

    private static final class Fixture {
        private final ChapterSelectionAssistanceMapper assistanceMapper = mock(ChapterSelectionAssistanceMapper.class);
        private final ChapterProseCandidateMapper candidateMapper = mock(ChapterProseCandidateMapper.class);
        private final ProsePlanningChangePackageMapper packageMapper = mock(ProsePlanningChangePackageMapper.class);
        private final ChapterOutlineQueryMapper outlineMapper = mock(ChapterOutlineQueryMapper.class);
        private final ChapterPlanVersionMapper planMapper = mock(ChapterPlanVersionMapper.class);
        private final ScenePlanVersionMapper sceneMapper = mock(ScenePlanVersionMapper.class);
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final ChapterOutlineEntity outline = outline();
        private final ChapterPlanVersionEntity plan = plan();
        private final ChapterProseCandidateEntity candidate = candidate();
        private final ProsePlanningChangePackageEntity changePackage = new ProsePlanningChangePackageEntity();
        private final ProsePlanningChangeServiceImpl service = new ProsePlanningChangeServiceImpl(
                assistanceMapper, candidateMapper, packageMapper, outlineMapper, planMapper, sceneMapper,
                new PlanningContentCodec(), objectMapper);

        private Fixture() {
            ChapterSelectionAssistanceEntity assistance = assistance();
            assistance.setPlanningContextJson(json(new PlanningContext(
                    10L, 2, 3, 20L, 4, "共 1 场", List.of(scene()))));
            when(assistanceMapper.selectById(9L)).thenReturn(assistance);
            when(assistanceMapper.selectByIdForUpdate(9L)).thenReturn(assistance);
            when(candidateMapper.selectByIdForUpdate(2L, 8L)).thenReturn(candidate);
            when(outlineMapper.findLatest(2L)).thenReturn(outline);
            when(planMapper.selectOne(any())).thenReturn(plan);
            ScenePlanVersionEntity persistedScene = new ScenePlanVersionEntity();
            persistedScene.setContentJson(json(scene()));
            persistedScene.setDeleted(0);
            when(sceneMapper.findAllByPlanId(20L)).thenReturn(List.of(persistedScene));
            when(outlineMapper.findLatestForUpdate(2L)).thenReturn(outline);
            when(planMapper.selectCurrentForUpdate(2L)).thenReturn(plan);
            doAnswer(invocation -> {
                ProsePlanningChangePackageEntity inserted = invocation.getArgument(0);
                inserted.setId(30L);
                copy(inserted, changePackage);
                return 1;
            }).when(packageMapper).insert(any(ProsePlanningChangePackageEntity.class));
            when(packageMapper.selectByIdForUpdate(2L, 30L)).thenReturn(changePackage);
            when(outlineMapper.findLatestForUpdate(2L)).thenReturn(outline);
            when(planMapper.selectCurrentForUpdate(2L)).thenReturn(plan);
            when(planMapper.supersedeCurrentIfVersion(20L, 4)).thenReturn(1);
            when(planMapper.selectMaxPlanNo(2L)).thenReturn(2);
            doAnswer(invocation -> {
                invocation.getArgument(0, ChapterPlanVersionEntity.class).setId(21L);
                return 1;
            }).when(planMapper).insert(any(ChapterPlanVersionEntity.class));
            when(packageMapper.markApplied(30L, 5, "new-hash", 21L, 0)).thenReturn(1);
        }

        private ModelPlanningProposal proposal() {
            return new ModelPlanningProposal("调整场景目标", "共 1 场", "调整后的场景", List.of(scene()));
        }

        private String json(Object value) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static ChapterSelectionAssistanceEntity assistance() {
            ChapterSelectionAssistanceEntity assistance = new ChapterSelectionAssistanceEntity();
            assistance.setId(9L);
            assistance.setWorkId(1L);
            assistance.setChapterId(2L);
            assistance.setOperationType("rewrite");
            assistance.setRequestStatus("ready");
            assistance.setTargetCandidateId(8L);
            assistance.setTargetContentVersion(4);
            assistance.setTargetContentHash("candidate-hash");
            assistance.setDeleted(0);
            return assistance;
        }

        private static ChapterOutlineEntity outline() {
            ChapterOutlineEntity outline = new ChapterOutlineEntity();
            outline.setId(10L);
            outline.setChapterId(2L);
            outline.setRevision(2);
            outline.setVersion(3);
            outline.setDeleted(0);
            return outline;
        }

        private static ChapterPlanVersionEntity plan() {
            ChapterPlanVersionEntity plan = new ChapterPlanVersionEntity();
            plan.setId(20L);
            plan.setWorkId(1L);
            plan.setChapterId(2L);
            plan.setPlanNo(2);
            plan.setNarrativePlanId(3L);
            plan.setNarrativePlanNo(1);
            plan.setOutlineId(10L);
            plan.setOutlineRevision(2);
            plan.setOutlineContentSchemaVersion(2);
            plan.setOutlineMigrationReviewStatus("not_required");
            plan.setAiTaskId(5L);
            plan.setPlanStatus("published");
            plan.setCurrentMarker(1);
            plan.setValidityStatus("current");
            plan.setVersion(4);
            plan.setDeleted(0);
            return plan;
        }

        private static ChapterProseCandidateEntity candidate() {
            ChapterProseCandidateEntity candidate = new ChapterProseCandidateEntity();
            candidate.setId(8L);
            candidate.setChapterId(2L);
            candidate.setVersion(4);
            candidate.setContentHash("candidate-hash");
            return candidate;
        }

        private static ScenePlanContent scene() {
            return new ScenePlanContent("scene-1", 1, "第一场", null, "当天", null,
                    "推进目标", "发生冲突", "紧张", "快速", List.of(), List.of(), List.of(),
                    "形成结果", "planned", List.of(), List.of(), List.of(), "", List.of(),
                    List.of(), "core", List.of(), List.of());
        }

        private static void copy(
                ProsePlanningChangePackageEntity source,
                ProsePlanningChangePackageEntity target) {
            target.setId(source.getId());
            target.setWorkId(source.getWorkId());
            target.setChapterId(source.getChapterId());
            target.setAssistanceId(source.getAssistanceId());
            target.setTargetCandidateId(source.getTargetCandidateId());
            target.setIdempotencyKey(source.getIdempotencyKey());
            target.setPackageStatus(source.getPackageStatus());
            target.setChangeSummary(source.getChangeSummary());
            target.setBeforeSummary(source.getBeforeSummary());
            target.setAfterSummary(source.getAfterSummary());
            target.setTargetCandidateVersion(source.getTargetCandidateVersion());
            target.setTargetCandidateHash(source.getTargetCandidateHash());
            target.setBaseOutlineId(source.getBaseOutlineId());
            target.setBaseOutlineRevision(source.getBaseOutlineRevision());
            target.setBaseOutlineVersion(source.getBaseOutlineVersion());
            target.setBaseScenePlanId(source.getBaseScenePlanId());
            target.setBaseScenePlanVersion(source.getBaseScenePlanVersion());
            target.setProposedScenesJson(source.getProposedScenesJson());
            target.setVersion(source.getVersion());
            target.setDeleted(0);
        }
    }
}
