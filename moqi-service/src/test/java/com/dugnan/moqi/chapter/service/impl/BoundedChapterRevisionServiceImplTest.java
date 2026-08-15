package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.dto.BoundedChapterRevisionModels.CreateBoundedRevisionRequest;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationFinding;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.BoundedChapterRevisionEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.BoundedChapterRevisionMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证整章有界修订的证据筛选、人工停止和候选不可变边界。
 */
class BoundedChapterRevisionServiceImplTest {

    @Test
    void createsOneTaskBriefForMultipleCompatibleFindings() throws Exception {
        Fixture fixture = new Fixture();
        fixture.report.setFindingsJson(fixture.objectMapper.writeValueAsString(List.of(
                fixture.finding("cause-1", "causality", 0.95D, true),
                fixture.finding("route-1", "continuity", 0.91D, true))));

        var result = fixture.service.create(12L, 3L,
                new CreateBoundedRevisionRequest(9L, "bounded-1"));

        assertThat(result.revisionStatus()).isEqualTo("queued");
        assertThat(result.findingKeys()).containsExactly("cause-1", "route-1");
        verify(fixture.runtime).start(any());
        verify(fixture.generationMapper, never()).updateById(
                any(ChapterGenerationEntity.class));
    }

    @Test
    void routesSourceConflictToNeedsHumanWithoutModelRun() throws Exception {
        Fixture fixture = new Fixture();
        fixture.report.setConclusion("needs_human");
        fixture.report.setFindingsJson(fixture.objectMapper.writeValueAsString(List.of(
                fixture.finding("source-1", "source_conflict", 0.95D, false))));

        var result = fixture.service.create(12L, 3L,
                new CreateBoundedRevisionRequest(9L, "bounded-human"));

        assertThat(result.revisionStatus()).isEqualTo("needs_human");
        assertThat(result.stopReason()).isEqualTo("source_conflict");
        verify(fixture.runtime, never()).start(any());
    }

    @Test
    void stopsWhenFindingBudgetIsExhausted() throws Exception {
        Fixture fixture = new Fixture();
        List<EvaluationFinding> findings = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(index -> fixture.finding("issue-" + index, "continuity", 0.9D, true))
                .toList();
        fixture.report.setFindingsJson(fixture.objectMapper.writeValueAsString(findings));

        var result = fixture.service.create(12L, 3L,
                new CreateBoundedRevisionRequest(9L, "bounded-budget"));

        assertThat(result.revisionStatus()).isEqualTo("needs_human");
        assertThat(result.stopReason()).isEqualTo("finding_budget_exhausted");
        verify(fixture.runtime, never()).start(any());
    }

    @Test
    void stopsLowConfidenceBlockingFindingForHumanDecision() throws Exception {
        Fixture fixture = new Fixture();
        fixture.report.setConclusion("needs_human");
        fixture.report.setFindingsJson(fixture.objectMapper.writeValueAsString(List.of(
                fixture.finding("uncertain-1", "continuity", 0.6D, false))));

        var result = fixture.service.create(12L, 3L,
                new CreateBoundedRevisionRequest(9L, "bounded-low-confidence"));

        assertThat(result.revisionStatus()).isEqualTo("needs_human");
        assertThat(result.stopReason()).isEqualTo("low_confidence_or_human_boundary");
    }

    @Test
    void rejectsSecondAutomaticRevisionForSameSourceGeneration() throws Exception {
        Fixture fixture = new Fixture();
        fixture.report.setFindingsJson(fixture.objectMapper.writeValueAsString(List.of(
                fixture.finding("cause-1", "causality", 0.95D, true))));
        BoundedChapterRevisionEntity previous = new BoundedChapterRevisionEntity();
        previous.setId(22L);
        when(fixture.revisionMapper.selectOne(any())).thenReturn(previous);

        assertThatThrownBy(() -> fixture.service.create(12L, 3L,
                new CreateBoundedRevisionRequest(9L, "bounded-2")))
                .isInstanceOf(BusinessException.class);
        verify(fixture.runtime, never()).start(any());
    }

    @Test
    void persistsNewCandidateWithoutUpdatingSourceGeneration() {
        Fixture fixture = new Fixture();
        org.mockito.Mockito.doAnswer(invocation -> {
            ChapterGenerationEntity candidate = invocation.getArgument(0);
            candidate.setId(4L);
            return 1;
        }).when(fixture.generationMapper).insert(any(ChapterGenerationEntity.class));

        Long candidateId = fixture.service.persistCandidate(7L, "修订后的完整正文", 81L);

        assertThat(candidateId).isEqualTo(4L);
        verify(fixture.generationMapper).insert(any(ChapterGenerationEntity.class));
        verify(fixture.generationMapper, never()).updateById(fixture.generation);
    }

    @Test
    void rejectsNoOpRevisionWithoutCreatingCandidate() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.persistCandidate(7L, "原始正文", 81L))
                .isInstanceOf(BusinessException.class);
        verify(fixture.generationMapper, never()).insert(any(ChapterGenerationEntity.class));
    }

    private static final class Fixture {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        private final ChapterGenerationEvaluationReportMapper reportMapper =
                mock(ChapterGenerationEvaluationReportMapper.class);
        private final BoundedChapterRevisionMapper revisionMapper = mock(BoundedChapterRevisionMapper.class);
        private final AiTaskMapper taskMapper = mock(AiTaskMapper.class);
        private final ChapterAssetSourceSnapshotMapper sourceSnapshotMapper =
                mock(ChapterAssetSourceSnapshotMapper.class);
        private final AgentRuntime runtime = mock(AgentRuntime.class);
        private final ChapterGenerationEntity generation = generation();
        private final ChapterGenerationEvaluationReportEntity report = report();
        private final BoundedChapterRevisionServiceImpl service = new BoundedChapterRevisionServiceImpl(
                generationMapper, reportMapper, revisionMapper, taskMapper, sourceSnapshotMapper, objectMapper);

        private Fixture() {
            report.setContentHash(serviceHash("原始正文"));
            service.setAgentRuntime(runtime);
            when(generationMapper.selectById(3L)).thenReturn(generation);
            when(reportMapper.selectById(9L)).thenReturn(report);
            org.mockito.Mockito.doAnswer(invocation -> {
                AiTaskEntity task = invocation.getArgument(0);
                task.setId(8L);
                return 1;
            }).when(taskMapper).insert(any(AiTaskEntity.class));
            org.mockito.Mockito.doAnswer(invocation -> {
                BoundedChapterRevisionEntity revision = invocation.getArgument(0);
                revision.setId(7L);
                return 1;
            }).when(revisionMapper).insert(any(BoundedChapterRevisionEntity.class));
            when(revisionMapper.selectById(7L)).thenAnswer(invocation -> {
                BoundedChapterRevisionEntity item = new BoundedChapterRevisionEntity();
                item.setId(7L);
                item.setSourceGenerationId(3L);
                item.setSourceReportId(9L);
                item.setRevisionStatus("queued");
                item.setFindingKeysJson("[\"cause-1\",\"route-1\"]");
                item.setRevisionBriefJson("{}");
                item.setAiTaskId(8L);
                item.setVersion(0);
                item.setDeleted(0);
                return item;
            });
            when(runtime.start(any())).thenReturn(new AgentRunView(6L,
                    BoundedChapterRevisionServiceImpl.WORKFLOW_TYPE, "queued", 1L, 12L, 8L,
                    "revise", 0L, null, null, null, null, null));
        }

        private EvaluationFinding finding(String key, String category, double confidence, boolean auto) {
            return new EvaluationFinding(key, category, "blocking", confidence, "llm", null,
                    "第2段", null, "问题" + key, "按证据修正", "冻结 Brief", "第2段", true, auto);
        }

        private String serviceHash(String value) {
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private ChapterGenerationEntity generation() {
            ChapterGenerationEntity item = new ChapterGenerationEntity();
            item.setId(3L);
            item.setWorkId(1L);
            item.setChapterId(12L);
            item.setGenerationStatus("preview");
            item.setGeneratedContent("原始正文");
            item.setVersion(2);
            item.setDeleted(0);
            return item;
        }

        private ChapterGenerationEvaluationReportEntity report() {
            ChapterGenerationEvaluationReportEntity item = new ChapterGenerationEvaluationReportEntity();
            item.setId(9L);
            item.setChapterId(12L);
            item.setGenerationId(3L);
            item.setReportStatus("ready");
            item.setConclusion("needs_revision");
            item.setContentHash("source-content-hash");
            item.setSourceFingerprint("source-fingerprint");
            item.setFindingsJson("[]");
            item.setDeleted(0);
            return item;
        }
    }
}
