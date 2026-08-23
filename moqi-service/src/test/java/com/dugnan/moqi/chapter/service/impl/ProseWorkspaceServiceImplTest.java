package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.SaveProseCandidateRequest;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseWorkspaceSelectionMapper;
import com.dugnan.moqi.chapter.selection.ProsePlanningChangeService;
import com.dugnan.moqi.chapter.selection.ProseProposalSettlementService;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.chapter.service.ProseCandidateMaterializationService;
import com.dugnan.moqi.chapter.service.ProseCandidateAdoptionService;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.AdoptionReadiness;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 验证正文候选只读目录、CAS 保存和每内容版本单次评价触发边界。
 */
class ProseWorkspaceServiceImplTest {

    @Test
    void keepsFailedQualityCandidateVisibleAndReadDoesNotCreateEvaluation() {
        Fixture fixture = new Fixture();
        ChapterGenerationEvaluationReportEntity report = new ChapterGenerationEvaluationReportEntity();
        report.setReportStatus("failed");
        report.setContentHash("candidate-hash");
        report.setGmtModified(LocalDateTime.now());
        when(fixture.candidateMapper.selectList(any())).thenReturn(List.of(fixture.candidate));
        when(fixture.generationMapper.selectList(any())).thenReturn(List.of());
        when(fixture.reportMapper.selectList(any())).thenReturn(List.of(report));

        var workspace = fixture.service.getWorkspace(12L);

        assertThat(workspace.candidates()).hasSize(1);
        assertThat(workspace.candidates().get(0).quality().status()).isEqualTo("failed");
        assertThat(workspace.formal().objectId()).isEqualTo("formal:12");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ChapterGenerationEvaluationReportEntity>> reportQuery =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fixture.reportMapper).selectList(reportQuery.capture());
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                ChapterGenerationEvaluationReportEntity.class);
        assertThat(reportQuery.getValue().getSqlSegment()).contains("generation_scene_id IS NULL");
        verifyNoInteractions(fixture.evaluationService);
    }

    @Test
    void savesSameCandidateWithCasAndRetryReusesOneHiddenQualitySnapshot() {
        Fixture fixture = new Fixture();
        when(fixture.candidateMapper.selectByIdForUpdate(12L, 8L)).thenReturn(fixture.candidate);
        when(fixture.candidateMapper.selectOne(any())).thenReturn(fixture.candidate);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.sourceGeneration);
        when(fixture.generationMapper.insert(any(ChapterGenerationEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChapterGenerationEntity.class).setId(99L);
            return 1;
        });
        when(fixture.candidateMapper.updateContentIfVersion(
                anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    fixture.candidate.setContent(invocation.getArgument(2));
                    fixture.candidate.setContentHash(invocation.getArgument(3));
                    fixture.candidate.setWordCount(invocation.getArgument(4));
                    fixture.candidate.setQualityGenerationId(invocation.getArgument(5));
                    fixture.candidate.setQualityRequestStatus("pending");
                    fixture.candidate.setVersion(5);
                    return 1;
                });
        when(fixture.reportMapper.selectList(any())).thenReturn(List.of());

        var saved = fixture.service.saveCandidate(
                12L, 8L, new SaveProseCandidateRequest("新候选正文", 4, null, false));
        var retried = fixture.service.saveCandidate(
                12L, 8L, new SaveProseCandidateRequest("新候选正文", 4, null, false));

        assertThat(saved.candidateId()).isEqualTo(8L);
        assertThat(saved.contentVersion()).isEqualTo(5);
        assertThat(saved.quality().status()).isEqualTo("pending");
        assertThat(retried.contentVersion()).isEqualTo(5);
        ArgumentCaptor<ChapterGenerationEntity> snapshot = ArgumentCaptor.forClass(ChapterGenerationEntity.class);
        verify(fixture.generationMapper).insert(snapshot.capture());
        assertThat(snapshot.getValue().getGenerationStatus()).isEqualTo("candidate_snapshot");
        assertThat(snapshot.getValue().getIdempotencyKey()).startsWith("prose-candidate:8:5:");
        verify(fixture.evaluationService, times(2)).create(
                org.mockito.ArgumentMatchers.eq(12L), org.mockito.ArgumentMatchers.eq(99L), any());
        verify(fixture.materializationService, times(2)).markQualityRequested(99L);
    }

    @Test
    void rejectsStaleCandidateVersionBeforeCreatingSnapshot() {
        Fixture fixture = new Fixture();
        when(fixture.candidateMapper.selectByIdForUpdate(12L, 8L)).thenReturn(fixture.candidate);

        assertThatThrownBy(() -> fixture.service.saveCandidate(
                12L, 8L, new SaveProseCandidateRequest("冲突正文", 3, null, false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PROSE_CANDIDATE_CONFLICT));

        verify(fixture.generationMapper, never()).insert(any(ChapterGenerationEntity.class));
        verifyNoInteractions(fixture.evaluationService);
    }

    @Test
    void marksReleasedFormalSlotReadOnly() {
        Fixture fixture = new Fixture();
        fixture.chapter.setCurrentProseRevisionId(20L);
        when(fixture.candidateMapper.selectList(any())).thenReturn(List.of());
        when(fixture.generationMapper.selectList(any())).thenReturn(List.of());

        assertThat(fixture.service.getWorkspace(12L).formal().editable()).isFalse();
    }

    @Test
    void rejectsIncompletePlanningConfirmationBeforeLockingCandidate() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.saveCandidate(
                12L, 8L, new SaveProseCandidateRequest("候选正文", 4, 7L, false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须同时提交");

        verify(fixture.candidateMapper, never()).selectByIdForUpdate(anyLong(), anyLong());
        verifyNoInteractions(fixture.planningChangeService);
    }

    @Test
    void appliesConfirmedPlanningPackageInsideCandidateSave() {
        Fixture fixture = new Fixture();
        when(fixture.candidateMapper.selectByIdForUpdate(12L, 8L)).thenReturn(fixture.candidate);
        when(fixture.candidateMapper.selectOne(any())).thenReturn(fixture.candidate);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.sourceGeneration);
        when(fixture.generationMapper.insert(any(ChapterGenerationEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChapterGenerationEntity.class).setId(99L);
            return 1;
        });
        when(fixture.candidateMapper.updateContentIfVersion(
                anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    fixture.candidate.setContent(invocation.getArgument(2));
                    fixture.candidate.setContentHash(invocation.getArgument(3));
                    fixture.candidate.setQualityGenerationId(invocation.getArgument(5));
                    fixture.candidate.setVersion(5);
                    return 1;
                });
        when(fixture.reportMapper.selectList(any())).thenReturn(List.of());

        fixture.service.saveCandidate(12L, 8L,
                new SaveProseCandidateRequest("联动候选", 4, 7L, true, List.of(9L)));

        verify(fixture.planningChangeService).apply(
                eq(12L), eq(fixture.candidate), eq(7L), eq(5), anyString(), eq(List.of(9L)));
        verify(fixture.candidateMapper).updateContentIfVersion(
                eq(12L), eq(8L), eq("联动候选"), anyString(), anyInt(), eq(99L), eq(4));
    }

    @Test
    void settlesExplicitAppliedProposalsWithSavedCandidateResult() {
        Fixture fixture = new Fixture();
        when(fixture.candidateMapper.selectByIdForUpdate(12L, 8L)).thenReturn(fixture.candidate);
        when(fixture.candidateMapper.selectOne(any())).thenReturn(fixture.candidate);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.sourceGeneration);
        when(fixture.generationMapper.insert(any(ChapterGenerationEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChapterGenerationEntity.class).setId(99L);
            return 1;
        });
        when(fixture.candidateMapper.updateContentIfVersion(
                anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyLong(), anyInt()))
                .thenReturn(1);

        fixture.service.saveCandidate(12L, 8L,
                new SaveProseCandidateRequest("应用提案后的候选", 4, null, false, List.of(9L)));

        verify(fixture.proposalSettlementService).validateForSave(12L, fixture.candidate, List.of(9L));
        verify(fixture.proposalSettlementService).markApplied(
                eq(12L), eq(fixture.candidate), eq(List.of(9L)), eq(5), anyString());
    }

    @Test
    void idempotentPlanningRetryRequiresSameAppliedPackageWithoutNewSnapshot() {
        Fixture fixture = new Fixture();
        fixture.candidate.setContent("联动候选");
        fixture.candidate.setContentHash(contentHash("联动候选"));
        fixture.candidate.setVersion(5);
        when(fixture.candidateMapper.selectByIdForUpdate(12L, 8L)).thenReturn(fixture.candidate);
        when(fixture.reportMapper.selectList(any())).thenReturn(List.of());

        fixture.service.saveCandidate(12L, 8L,
                new SaveProseCandidateRequest("联动候选", 4, 7L, true, List.of(9L)));

        verify(fixture.planningChangeService).requireApplied(
                12L, 8L, 7L, 5, contentHash("联动候选"), List.of(9L));
        verify(fixture.generationMapper, never()).insert(any(ChapterGenerationEntity.class));
        verify(fixture.planningChangeService, never()).apply(any(), any(), any(), any(), any(), any());
    }

    @Test
    void candidateCasFailureRaisesRollbackAfterPlanningApply() {
        Fixture fixture = new Fixture();
        when(fixture.candidateMapper.selectByIdForUpdate(12L, 8L)).thenReturn(fixture.candidate);
        when(fixture.candidateMapper.selectOne(any())).thenReturn(fixture.candidate);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.sourceGeneration);
        when(fixture.generationMapper.insert(any(ChapterGenerationEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChapterGenerationEntity.class).setId(99L);
            return 1;
        });
        when(fixture.candidateMapper.updateContentIfVersion(
                anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyLong(), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.saveCandidate(12L, 8L,
                new SaveProseCandidateRequest("联动冲突", 4, 7L, true, List.of(9L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("候选已被更新");

        verify(fixture.planningChangeService).apply(
                eq(12L), eq(fixture.candidate), eq(7L), eq(5), anyString(), eq(List.of(9L)));
        verify(fixture.proposalSettlementService).markApplied(
                eq(12L), eq(fixture.candidate), eq(List.of(9L)), eq(5), anyString());
    }

    @Test
    void projectsOnlyFrozenAuthorVisibleBasisAndMarksEditedCandidate() {
        Fixture fixture = new Fixture();
        fixture.sourceGeneration.setGeneratedContent("创建时正文");
        fixture.sourceGeneration.setBasisSnapshotJson("""
                {"temperature":0.8,"executionConfig":{"secret":"hidden"},
                 "chapterGenerationBrief":{"workId":1,"content":"完整 Prompt", "fingerprint":"internal",
                   "chapterPurpose":"推进调查","chapterGoal":"找到线索","coreConflict":"是否信任证人",
                   "openingConditions":["雨夜"],"requiredEndingState":["拿到钥匙"],
                   "eventCausality":["追踪导致暴露"],"stateChanges":["主角开始怀疑"],
                   "characterConstraints":["林风保持克制"],
                   "entityExplanations":[{"sourceId":99,"type":"地点","name":"旧站","explanation":"封闭车站"}],
                   "creativeFreedom":["允许调整节奏"],"prohibitedInventions":["不得新增超能力"]},
                 "currentProseBasis":{"content":"前文","contentHash":"previous-hash"}}
                """);
        when(fixture.candidateMapper.selectOne(any())).thenReturn(fixture.candidate);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.sourceGeneration);

        var basis = fixture.service.getCandidateBasis(12L, 8L);

        assertThat(basis.basisStatus()).isEqualTo("complete");
        assertThat(basis.editedAfterCreation()).isTrue();
        assertThat(basis.outline().toString()).contains("推进调查").doesNotContain("workId", "Prompt");
        assertThat(basis.worldSettings().toString()).contains("旧站").doesNotContain("sourceId");
        assertThat(basis.previousProse().toString()).contains("前文", "previous-hash");
        assertThat(basis.toString()).doesNotContain("temperature", "executionConfig", "fingerprint");
    }

    @Test
    void legacyBasisDoesNotReadCurrentMaterialsToFillMissingFields() {
        Fixture fixture = new Fixture();
        fixture.sourceGeneration.setGeneratedContent("旧候选");
        fixture.sourceGeneration.setBasisSnapshotJson("{\"outlineContent\":\"旧格式章纲\"}");
        fixture.candidate.setContentHash(contentHash("旧候选"));
        when(fixture.candidateMapper.selectOne(any())).thenReturn(fixture.candidate);
        when(fixture.generationMapper.selectById(3L)).thenReturn(fixture.sourceGeneration);

        var basis = fixture.service.getCandidateBasis(12L, 8L);

        assertThat(basis.basisStatus()).isEqualTo("legacy_limited");
        assertThat(basis.editedAfterCreation()).isFalse();
        assertThat(basis.outline().isEmpty()).isTrue();
    }

    @Test
    void comparesFormalAndStableRootCandidateWithModifiedTime() {
        Fixture fixture = new Fixture();
        when(fixture.candidateMapper.selectOne(any())).thenReturn(fixture.candidate);

        var comparison = fixture.service.compare(12L, "formal:12", "candidate:8");

        assertThat(comparison.left().objectKind()).isEqualTo("formal");
        assertThat(comparison.right().rootCandidateId()).isEqualTo(8L);
        assertThat(comparison.right().sourceGenerationId()).isEqualTo(3L);
        assertThat(comparison.right().modifiedAt()).isEqualTo(fixture.candidate.getGmtModified());
    }

    private static String contentHash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class Fixture {
        private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
        private final ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        private final ChapterGenerationEvaluationReportMapper reportMapper =
                mock(ChapterGenerationEvaluationReportMapper.class);
        private final ChapterProseCandidateMapper candidateMapper = mock(ChapterProseCandidateMapper.class);
        private final ChapterProseWorkspaceSelectionMapper selectionMapper =
                mock(ChapterProseWorkspaceSelectionMapper.class);
        private final ChapterAssetSourceSnapshotMapper sourceSnapshotMapper =
                mock(ChapterAssetSourceSnapshotMapper.class);
        private final GenerationEvaluationService evaluationService = mock(GenerationEvaluationService.class);
        private final ProseCandidateMaterializationService materializationService =
                mock(ProseCandidateMaterializationService.class);
        private final ProsePlanningChangeService planningChangeService = mock(ProsePlanningChangeService.class);
        private final ProseProposalSettlementService proposalSettlementService =
                mock(ProseProposalSettlementService.class);
        private final ProseCandidateAdoptionService adoptionService = mock(ProseCandidateAdoptionService.class);
        private final ChapterEntity chapter = chapter();
        private final ChapterProseCandidateEntity candidate = candidate();
        private final ChapterGenerationEntity sourceGeneration = generation();
        private final ProseWorkspaceServiceImpl service = new ProseWorkspaceServiceImpl(
                chapterMapper, generationMapper, reportMapper, candidateMapper, selectionMapper, sourceSnapshotMapper,
                evaluationService, materializationService);

        private Fixture() {
            service.setPlanningChangeService(planningChangeService);
            service.setProposalSettlementService(proposalSettlementService);
            service.setAdoptionService(adoptionService);
            service.setObjectMapper(new ObjectMapper());
            when(adoptionService.readiness(any())).thenReturn(
                    new AdoptionReadiness(false, "direct_formal", null, List.of("quality_report_missing"),
                            List.of("resolve_quality_gate")));
            when(chapterMapper.selectById(12L)).thenReturn(chapter);
        }

        private static ChapterEntity chapter() {
            ChapterEntity chapter = new ChapterEntity();
            chapter.setId(12L);
            chapter.setWorkId(1L);
            chapter.setContent("正式正文");
            chapter.setVersion(6);
            chapter.setDeleted(0);
            chapter.setGmtModified(LocalDateTime.now());
            return chapter;
        }

        private static ChapterProseCandidateEntity candidate() {
            ChapterProseCandidateEntity candidate = new ChapterProseCandidateEntity();
            candidate.setId(8L);
            candidate.setWorkId(1L);
            candidate.setChapterId(12L);
            candidate.setRootCandidateId(8L);
            candidate.setSourceKind("generation");
            candidate.setSourceGenerationId(3L);
            candidate.setQualityGenerationId(3L);
            candidate.setQualityRequestStatus("requested");
            candidate.setCandidateStatus("active");
            candidate.setAdoptionStatus("unadopted");
            candidate.setContent("旧候选");
            candidate.setContentHash("candidate-hash");
            candidate.setWordCount(4);
            candidate.setVersion(4);
            candidate.setDeleted(0);
            candidate.setGmtCreate(LocalDateTime.now());
            candidate.setGmtModified(LocalDateTime.now());
            return candidate;
        }

        private static ChapterGenerationEntity generation() {
            ChapterGenerationEntity generation = new ChapterGenerationEntity();
            generation.setId(3L);
            generation.setWorkId(1L);
            generation.setChapterId(12L);
            generation.setGenerationMode("full_draft");
            generation.setSelectionMode("all");
            generation.setGenerationStatus("preview");
            generation.setGeneratedContent("旧候选");
            generation.setContentAssemblyMode("whole_chapter_once");
            generation.setDeleted(0);
            generation.setVersion(2);
            return generation;
        }
    }
}
