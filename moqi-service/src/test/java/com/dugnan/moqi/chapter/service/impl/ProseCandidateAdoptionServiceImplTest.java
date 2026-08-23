package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.AdoptProseCandidateRequest;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;
import com.dugnan.moqi.chapter.entity.ProseCandidateAdoptionEntity;
import com.dugnan.moqi.chapter.entity.ProsePlanningChangePackageEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import com.dugnan.moqi.chapter.mapper.ProseCandidateAdoptionMapper;
import com.dugnan.moqi.chapter.mapper.ProsePlanningChangePackageMapper;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.impact.ProseImpactModels.CreateReportResult;
import com.dugnan.moqi.impact.ProseImpactModels.ReportView;
import com.dugnan.moqi.impact.ProseImpactService;
import com.dugnan.moqi.release.StoryReleaseModels.CandidateAdoptionDraft;
import com.dugnan.moqi.release.StoryReleaseService;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-08-23
 * @description 验证候选采纳门禁、未发布 CAS、已发布发布链路和幂等重放。
 */
class ProseCandidateAdoptionServiceImplTest {

    @Test
    void adoptsUnpublishedCandidateWithFormalCasAndFrozenRecord() {
        Fixture fixture = new Fixture(false);

        var result = fixture.service.adopt(12L, 8L, fixture.request());

        assertThat(result.adoptionMode()).isEqualTo("direct_formal");
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.formalVersion()).isEqualTo(7);
        verify(fixture.chapterMapper).updateContentIfVersion(12L, "候选正文", 6);
        verify(fixture.candidateMapper).markAdoptionStatus(12L, 8L, 4, Fixture.hash("候选正文"), "adopted");
        verify(fixture.storyReleaseService, never()).ensureCandidateAdoptionDraft(any(), any(), any());
        var locks = inOrder(fixture.workMapper, fixture.chapterMapper, fixture.candidateMapper,
                fixture.reportMapper, fixture.assistanceMapper, fixture.planningMapper);
        locks.verify(fixture.workMapper).selectByIdForUpdate(1L);
        locks.verify(fixture.chapterMapper).selectByIdForUpdate(12L);
        locks.verify(fixture.candidateMapper).selectByIdForUpdate(12L, 8L);
        locks.verify(fixture.reportMapper).selectByIdForUpdate(12L, 9L);
        locks.verify(fixture.assistanceMapper).selectPendingForAdoption(12L, 8L);
        locks.verify(fixture.planningMapper).selectPendingForAdoption(12L, 8L);
    }

    @Test
    void rejectsOldOrNonWholeQualityReportBeforeFormalWrite() {
        Fixture fixture = new Fixture(false);
        fixture.report.setGenerationSceneId(3L);

        assertThatThrownBy(() -> fixture.service.adopt(12L, 8L, fixture.request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PROSE_ADOPTION_BLOCKED));

        verify(fixture.chapterMapper, never()).updateContentIfVersion(any(), any(), any());
    }

    @Test
    void acceptsPassQualityReportAsWellAsWarning() {
        Fixture fixture = new Fixture(false);
        fixture.report.setConclusion("pass");

        assertThat(fixture.service.adopt(12L, 8L, fixture.request()).status()).isEqualTo("completed");
    }

    @ParameterizedTest
    @CsvSource({
            "queued, warning",
            "running, warning",
            "ready, fail",
            "unavailable, warning"
    })
    void blocksNonAdoptableQualityStates(String reportStatus, String conclusion) {
        Fixture fixture = new Fixture(false);
        fixture.report.setReportStatus(reportStatus);
        fixture.report.setConclusion(conclusion);

        assertThatThrownBy(() -> fixture.service.adopt(12L, 8L, fixture.request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PROSE_ADOPTION_BLOCKED));

        verify(fixture.chapterMapper, never()).updateContentIfVersion(any(), any(), any());
    }

    @Test
    void blocksQualityReportForDifferentContentHash() {
        Fixture fixture = new Fixture(false);
        fixture.report.setContentHash(Fixture.hash("旧候选正文"));

        assertThatThrownBy(() -> fixture.service.adopt(12L, 8L, fixture.request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PROSE_ADOPTION_BLOCKED));
    }

    @Test
    void blocksPendingProposalAndCandidatePlanningPackage() {
        Fixture proposalFixture = new Fixture(false);
        when(proposalFixture.assistanceMapper.selectPendingForAdoption(12L, 8L))
                .thenReturn(List.of(new ChapterSelectionAssistanceEntity()));

        assertThatThrownBy(() -> proposalFixture.service.adopt(12L, 8L, proposalFixture.request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PROSE_ADOPTION_BLOCKED));

        Fixture planningFixture = new Fixture(false);
        when(planningFixture.planningMapper.selectPendingForAdoption(12L, 8L))
                .thenReturn(List.of(new ProsePlanningChangePackageEntity()));

        assertThatThrownBy(() -> planningFixture.service.adopt(12L, 8L, planningFixture.request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PROSE_ADOPTION_BLOCKED));
    }

    @Test
    void blocksFormalVersionMismatchAndCasConflict() {
        Fixture staleFixture = new Fixture(false);
        AdoptProseCandidateRequest stale = new AdoptProseCandidateRequest(
                4, Fixture.hash("候选正文"), 5, 9L, "adopt-stale", true);
        assertThatThrownBy(() -> staleFixture.service.adopt(12L, 8L, stale))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PROSE_ADOPTION_CONFLICT));

        Fixture casFixture = new Fixture(false);
        when(casFixture.chapterMapper.updateContentIfVersion(12L, "候选正文", 6)).thenReturn(0);
        assertThatThrownBy(() -> casFixture.service.adopt(12L, 8L, casFixture.request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PROSE_ADOPTION_CONFLICT));
        verify(casFixture.candidateMapper, never()).markAdoptionStatus(any(), any(), any(), any(), any());
    }

    @Test
    void differentIdempotencyKeyCannotReuseSameCandidateVersion() {
        Fixture fixture = new Fixture(false);
        ProseCandidateAdoptionEntity existing = fixture.adoption("completed");
        when(fixture.adoptionMapper.selectOne(any())).thenReturn(existing);
        AdoptProseCandidateRequest competing = new AdoptProseCandidateRequest(
                4, Fixture.hash("候选正文"), 6, 9L, "adopt-2", true);

        assertThatThrownBy(() -> fixture.service.adopt(12L, 8L, competing))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PROSE_ADOPTION_CONFLICT));

        verify(fixture.adoptionMapper, never()).insert(any(ProseCandidateAdoptionEntity.class));
    }

    @Test
    void rejectsCandidateWhenPersistedHashDoesNotMatchLockedContent() {
        Fixture fixture = new Fixture(false);
        fixture.candidate.setContent("数据库中被破坏的正文");

        assertThatThrownBy(() -> fixture.service.adopt(12L, 8L, fixture.request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PROSE_ADOPTION_CONFLICT));

        verify(fixture.reportMapper, never()).selectByIdForUpdate(any(), any());
        verify(fixture.chapterMapper, never()).updateContentIfVersion(any(), any(), any());
    }

    @Test
    void rejectsDeletedChapterAfterOwningScopeIsLocked() {
        Fixture fixture = new Fixture(false);
        fixture.chapter.setDeleted(1);

        assertThatThrownBy(() -> fixture.service.adopt(12L, 8L, fixture.request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAPTER_NOT_FOUND));

        verify(fixture.candidateMapper, never()).selectByIdForUpdate(any(), any());
    }

    @Test
    void reusesBoundedRevisionAdoptableGateBeforeFormalWrite() {
        Fixture fixture = new Fixture(false);
        doThrow(new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "有界修订来源不可采纳"))
                .when(fixture.evaluationService).requireAdoptable(12L, 5L);

        assertThatThrownBy(() -> fixture.service.adopt(12L, 8L, fixture.request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("有界修订来源不可采纳");

        verify(fixture.chapterMapper, never()).updateContentIfVersion(any(), any(), any());
        verify(fixture.adoptionMapper, never()).insert(any(ProseCandidateAdoptionEntity.class));
    }

    @Test
    void readinessExposesBoundedSourceGateAsStableBlockingCode() {
        Fixture fixture = new Fixture(false);
        doThrow(new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "来源已失效"))
                .when(fixture.evaluationService).requireAdoptable(12L, 5L);

        var readiness = fixture.service.readiness(fixture.candidate);

        assertThat(readiness.canAdopt()).isFalse();
        assertThat(readiness.blockingCodes()).contains("quality_source_not_adoptable");
        assertThat(readiness.nextActions()).contains("resolve_quality_gate");
    }

    @Test
    void publishedCandidateCreatesRevisionWorkspaceThenStartsOneIdempotentImpact() {
        Fixture fixture = new Fixture(true);
        when(fixture.storyReleaseService.ensureCandidateAdoptionDraft(any(), any(), any()))
                .thenReturn(new CandidateAdoptionDraft(31L, 41L, 2));
        CreateReportResult impact = mock(CreateReportResult.class);
        ReportView reportView = mock(ReportView.class);
        when(reportView.id()).thenReturn(51L);
        when(impact.report()).thenReturn(reportView);
        when(fixture.impactService.create(any(), any(), any(), any())).thenReturn(impact);

        TransactionSynchronizationManager.initSynchronization();
        var result = fixture.service.adopt(12L, 8L, fixture.request());

        assertThat(result.adoptionMode()).isEqualTo("revision_release");
        assertThat(result.revisionId()).isEqualTo(31L);
        assertThat(result.workspaceId()).isEqualTo(41L);
        verify(fixture.chapterMapper, never()).updateContentIfVersion(any(), any(), any());
        verify(fixture.impactService, never()).create(any(), any(), any(), any());
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        verify(fixture.impactService).create(any(), any(), any(), any());
        verify(fixture.adoptionMapper).bindImpactReport(77L, 51L);
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void repeatedSameCandidateAdoptionReusesFrozenResultWithoutDuplicateSideEffects() {
        Fixture fixture = new Fixture(false);
        ProseCandidateAdoptionEntity replay = fixture.adoption("completed");
        when(fixture.adoptionMapper.selectReplayForUpdate(12L, "adopt-1")).thenReturn(replay);

        var result = fixture.service.adopt(12L, 8L, fixture.request());

        assertThat(result.adoptionId()).isEqualTo(77L);
        verify(fixture.adoptionMapper, never()).insert(any(ProseCandidateAdoptionEntity.class));
        verify(fixture.reportMapper, never()).selectByIdForUpdate(any(), any());
        verify(fixture.chapterMapper, never()).updateContentIfVersion(any(), any(), any());
    }

    @Test
    void impactStartFailureKeepsPendingRecordWithSafeRecoveryCode() {
        Fixture fixture = new Fixture(true);
        when(fixture.storyReleaseService.ensureCandidateAdoptionDraft(any(), any(), any()))
                .thenReturn(new CandidateAdoptionDraft(31L, 41L, 2));
        when(fixture.impactService.create(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.MODEL_UNAVAILABLE, "provider secret detail"));

        fixture.service.adopt(12L, 8L, fixture.request());

        verify(fixture.adoptionMapper).markImpactStartFailed(77L, "impact_start_unavailable");
        verify(fixture.adoptionMapper, never()).bindImpactReport(any(), any());
    }

    private static final class Fixture {
        private final ChapterProseCandidateMapper candidateMapper = mock(ChapterProseCandidateMapper.class);
        private final ChapterGenerationEvaluationReportMapper reportMapper =
                mock(ChapterGenerationEvaluationReportMapper.class);
        private final ChapterSelectionAssistanceMapper assistanceMapper = mock(ChapterSelectionAssistanceMapper.class);
        private final ProsePlanningChangePackageMapper planningMapper = mock(ProsePlanningChangePackageMapper.class);
        private final ProseCandidateAdoptionMapper adoptionMapper = mock(ProseCandidateAdoptionMapper.class);
        private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
        private final WorkMapper workMapper = mock(WorkMapper.class);
        private final StoryReleaseService storyReleaseService = mock(StoryReleaseService.class);
        private final ProseImpactService impactService = mock(ProseImpactService.class);
        private final GenerationEvaluationService evaluationService = mock(GenerationEvaluationService.class);
        private final ChapterProseCandidateEntity candidate = candidate();
        private final ChapterGenerationEvaluationReportEntity report = report();
        private final ChapterEntity chapter;
        private final ProseCandidateAdoptionServiceImpl service = new ProseCandidateAdoptionServiceImpl(
                candidateMapper, reportMapper, assistanceMapper, planningMapper, adoptionMapper,
                chapterMapper, workMapper, storyReleaseService, impactService, evaluationService);

        private Fixture(boolean published) {
            chapter = chapter(published);
            when(candidateMapper.selectById(8L)).thenReturn(candidate);
            WorkEntity work = new WorkEntity();
            work.setId(1L);
            work.setDeleted(0);
            when(workMapper.selectByIdForUpdate(1L)).thenReturn(work);
            when(candidateMapper.selectByIdForUpdate(12L, 8L)).thenReturn(candidate);
            when(reportMapper.selectByIdForUpdate(12L, 9L)).thenReturn(report);
            when(reportMapper.selectWholeReportsForUpdate(5L)).thenReturn(List.of(report));
            when(reportMapper.selectOne(any())).thenReturn(report);
            when(assistanceMapper.selectPendingForAdoption(12L, 8L)).thenReturn(List.of());
            when(planningMapper.selectPendingForAdoption(12L, 8L)).thenReturn(List.of());
            when(chapterMapper.selectById(12L)).thenReturn(chapter);
            when(chapterMapper.selectByIdForUpdate(12L)).thenReturn(chapter);
            when(chapterMapper.updateContentIfVersion(12L, "候选正文", 6)).thenReturn(1);
            when(candidateMapper.markAdoptionStatus(any(), any(), any(), any(), any())).thenReturn(1);
            when(adoptionMapper.insert(any(ProseCandidateAdoptionEntity.class))).thenAnswer(invocation -> {
                ProseCandidateAdoptionEntity value = invocation.getArgument(0);
                value.setId(77L);
                value.setGmtModified(LocalDateTime.now());
                return 1;
            });
        }

        private AdoptProseCandidateRequest request() {
            return new AdoptProseCandidateRequest(4, hash("候选正文"), 6, 9L, "adopt-1", true);
        }

        private ProseCandidateAdoptionEntity adoption(String status) {
            ProseCandidateAdoptionEntity value = new ProseCandidateAdoptionEntity();
            value.setId(77L);
            value.setChapterId(12L);
            value.setCandidateId(8L);
            value.setCandidateVersion(4);
            value.setCandidateContentHash(hash("候选正文"));
            value.setExpectedFormalVersion(6);
            value.setQualityReportId(9L);
            value.setIdempotencyKey("adopt-1");
            value.setAdoptionMode("direct_formal");
            value.setAdoptionStatus(status);
            return value;
        }

        private static ChapterProseCandidateEntity candidate() {
            ChapterProseCandidateEntity value = new ChapterProseCandidateEntity();
            value.setId(8L);
            value.setWorkId(1L);
            value.setChapterId(12L);
            value.setVersion(4);
            value.setContent("候选正文");
            value.setContentHash(hash("候选正文"));
            value.setQualityGenerationId(5L);
            value.setCandidateStatus("active");
            value.setAdoptionStatus("unadopted");
            return value;
        }

        private static ChapterGenerationEvaluationReportEntity report() {
            ChapterGenerationEvaluationReportEntity value = new ChapterGenerationEvaluationReportEntity();
            value.setId(9L);
            value.setChapterId(12L);
            value.setGenerationId(5L);
            value.setReportStatus("ready");
            value.setConclusion("warning");
            value.setContentHash(hash("候选正文"));
            value.setDeleted(0);
            return value;
        }

        private static ChapterEntity chapter(boolean published) {
            ChapterEntity value = new ChapterEntity();
            value.setId(12L);
            value.setWorkId(1L);
            value.setVersion(6);
            value.setCurrentProseRevisionId(published ? 21L : null);
            value.setDeleted(0);
            return value;
        }

        private static String hash(String content) {
            try {
                return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(content.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
