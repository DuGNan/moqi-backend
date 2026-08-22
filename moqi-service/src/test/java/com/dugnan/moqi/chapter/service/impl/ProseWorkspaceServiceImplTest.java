package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.chapter.service.ProseCandidateMaterializationService;
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
        private final ChapterEntity chapter = chapter();
        private final ChapterProseCandidateEntity candidate = candidate();
        private final ChapterGenerationEntity sourceGeneration = generation();
        private final ProseWorkspaceServiceImpl service = new ProseWorkspaceServiceImpl(
                chapterMapper, generationMapper, reportMapper, candidateMapper, selectionMapper, sourceSnapshotMapper,
                evaluationService, materializationService);

        private Fixture() {
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
