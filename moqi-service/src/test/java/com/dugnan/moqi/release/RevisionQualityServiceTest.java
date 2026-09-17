package com.dugnan.moqi.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationReportView;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.RetryEvaluationRequest;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.release.entity.ChapterProseRevisionEntity;
import com.dugnan.moqi.release.mapper.ChapterProseRevisionMapper;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-09-15
 * @description 验证手工修订评价的冻结来源、幂等复用及失败恢复门禁。
 */
class RevisionQualityServiceTest {
    private final WorkMapper works = mock(WorkMapper.class);
    private final ChapterMapper chapters = mock(ChapterMapper.class);
    private final ChapterProseRevisionMapper revisions = mock(ChapterProseRevisionMapper.class);
    private final ChapterGenerationMapper generations = mock(ChapterGenerationMapper.class);
    private final ChapterAssetSourceSnapshotMapper snapshots = mock(ChapterAssetSourceSnapshotMapper.class);
    private final GenerationEvaluationService evaluations = mock(GenerationEvaluationService.class);
    private final RevisionQualityService service = new RevisionQualityService(
            works, chapters, revisions, generations, snapshots, evaluations);
    private final ChapterProseRevisionEntity revision = new ChapterProseRevisionEntity();
    private final ChapterGenerationEntity source = new ChapterGenerationEntity();

    RevisionQualityServiceTest() throws Exception {
        revision.setId(12L);
        revision.setWorkId(1L);
        revision.setChapterId(2L);
        revision.setParentRevisionId(10L);
        revision.setSourceGenerationId(4L);
        revision.setRevisionOrigin("manual");
        revision.setRevisionStatus("draft");
        revision.setContent("新的手工正文");
        revision.setContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(revision.getContent().getBytes(StandardCharsets.UTF_8))));
        revision.setVersion(0);
        revision.setDeleted(0);
        ChapterEntity chapter = new ChapterEntity();
        chapter.setCurrentProseRevisionId(10L);
        when(works.selectOne(any())).thenReturn(new WorkEntity());
        when(chapters.selectOne(any())).thenReturn(chapter);
        when(revisions.selectOne(any())).thenReturn(revision);
        when(revisions.update(eq(null), any())).thenReturn(1);
        source.setId(4L);
        source.setWorkId(1L);
        source.setChapterId(2L);
        source.setGeneratedContent("旧的已发布正文");
        source.setBasisSnapshotJson("{\"frozen\":true}");
        source.setDeleted(0);
        when(generations.selectById(4L)).thenReturn(source);
        doAnswer(call -> {
            ChapterGenerationEntity entity = call.getArgument(0);
            entity.setId(30L);
            return 1;
        }).when(generations).insert(any(ChapterGenerationEntity.class));
    }

    @Test
    void freezesEditedTextWithoutOverwritingOriginalSource() {
        service.start(1L, 2L, 12L, 0);
        ArgumentCaptor<ChapterGenerationEntity> capture = ArgumentCaptor.forClass(ChapterGenerationEntity.class);
        verify(generations).insert(capture.capture());
        assertThat(capture.getValue().getGeneratedContent()).isEqualTo("新的手工正文");
        assertThat(capture.getValue().getBaseGenerationId()).isEqualTo(4L);
        assertThat(capture.getValue().getGenerationStatus()).isEqualTo("candidate_snapshot");
        assertThat(capture.getValue().getBasisSnapshotJson()).isEqualTo(source.getBasisSnapshotJson());
        assertThat(source.getGeneratedContent()).isEqualTo("旧的已发布正文");
        assertThat(revision.getSourceGenerationId()).isEqualTo(4L);
        verify(evaluations).create(eq(2L), eq(30L), any());
    }

    @Test
    void replaysStartedEvaluationWithoutCreatingAnotherSnapshot() {
        useSnapshot();
        EvaluationReportView report = mock(EvaluationReportView.class);
        when(evaluations.latest(2L, 30L, null)).thenReturn(report);
        assertThat(service.start(1L, 2L, 12L, 0)).isSameAs(report);
        verify(generations, never()).insert(any(ChapterGenerationEntity.class));
        verify(evaluations, never()).create(any(), any(), any());
    }

    @Test
    void refusesStaleVersionBeforeCreatingSnapshot() {
        assertThatThrownBy(() -> service.start(1L, 2L, 12L, 9)).isInstanceOf(BusinessException.class);
        verify(generations, never()).insert(any(ChapterGenerationEntity.class));
    }

    @Test
    void refusesCrossChapterSourceAndCorruptedContent() {
        source.setChapterId(99L);
        assertThatThrownBy(() -> service.start(1L, 2L, 12L, 0)).isInstanceOf(BusinessException.class);
        source.setChapterId(2L);
        revision.setContent("内容已损坏");
        assertThatThrownBy(() -> service.start(1L, 2L, 12L, 0)).isInstanceOf(BusinessException.class);
        verify(evaluations, never()).create(any(), any(), any());
    }

    @Test
    void latestBeforeStartDoesNotExposeOriginalTextEvaluation() {
        assertThat(service.latest(1L, 2L, 12L)).isNull();
        verify(evaluations, never()).latest(any(), any(), any());
    }

    @Test
    void refusesAbandonedOrChangedPublishedBaseline() {
        revision.setRevisionStatus("abandoned");
        assertThatThrownBy(() -> service.start(1L, 2L, 12L, 0)).isInstanceOf(BusinessException.class);
        revision.setRevisionStatus("draft");
        revision.setParentRevisionId(9L);
        assertThatThrownBy(() -> service.start(1L, 2L, 12L, 0)).isInstanceOf(BusinessException.class);
    }

    @Test
    void staleRetryAttemptDoesNotInvokeRuntimeButCurrentAttemptDoes() {
        useSnapshot();
        EvaluationReportView report = mock(EvaluationReportView.class);
        when(report.id()).thenReturn(50L);
        when(report.retryable()).thenReturn(true);
        when(report.currentAttempt()).thenReturn(2);
        when(evaluations.get(2L, 30L, 50L)).thenReturn(report);
        when(evaluations.latest(2L, 30L, null)).thenReturn(report);
        assertThatThrownBy(() -> service.retry(1L, 2L, 12L, 50L, new RetryEvaluationRequest(1)))
                .isInstanceOf(BusinessException.class);
        verify(evaluations, never()).retry(any(), any(), any(), any());
        service.retry(1L, 2L, 12L, 50L, new RetryEvaluationRequest(2));
        verify(evaluations).retry(2L, 30L, 50L, new RetryEvaluationRequest(2));
    }

    private void useSnapshot() {
        revision.setQualityGenerationId(30L);
        source.setGeneratedContent(revision.getContent());
        when(generations.selectById(30L)).thenReturn(source);
    }

    @Test
    void preservesAnAlreadyBoundLegacyManualEvaluation() {
        revision.setEvaluationReportId(50L);
        source.setGeneratedContent(revision.getContent());
        EvaluationReportView report = mock(EvaluationReportView.class);
        when(evaluations.get(2L, 4L, 50L)).thenReturn(report);
        assertThat(service.start(1L, 2L, 12L, 0)).isSameAs(report);
        verify(generations, never()).insert(any(ChapterGenerationEntity.class));
    }
}
