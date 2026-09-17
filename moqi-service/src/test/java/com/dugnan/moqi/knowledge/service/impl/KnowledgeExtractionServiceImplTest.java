package com.dugnan.moqi.knowledge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.service.GenerationRetryMetadataResolver;
import com.dugnan.moqi.chapter.service.GenerationRetryMetadataResolver.RetryMetadata;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.Evidence;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ExtractedCandidate;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ExtractionOutput;
import com.dugnan.moqi.knowledge.entity.SettingEntryEntity;
import com.dugnan.moqi.knowledge.entity.StoryKnowledgeExtractionBatchEntity;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeCandidateMapper;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeExtractionBatchMapper;
import com.dugnan.moqi.release.entity.ChapterProseRevisionEntity;
import com.dugnan.moqi.release.mapper.ChapterProseRevisionMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * 验证已采纳正文知识提取的来源冻结与结构化输出边界。
 */
class KnowledgeExtractionServiceImplTest {

    private StoryKnowledgeExtractionBatchMapper batchMapper;
    private ChapterGenerationMapper generationMapper;
    private ChapterMapper chapterMapper;
    private ChapterProseRevisionMapper proseRevisionMapper;
    private WorkMapper workMapper;
    private AiTaskMapper taskMapper;
    private SettingEntryMapper settingMapper;
    private KnowledgeExtractionStaleMarker staleMarker;
    private GenerationRetryMetadataResolver retryMetadataResolver;
    private KnowledgeExtractionServiceImpl service;
    private StoryKnowledgeCandidateMapper candidateMapper;
    private ChapterSummaryMapper summaryMapper;
    private ForeshadowingItemMapper foreshadowingMapper;
    private ChapterKeyEventMapper eventMapper;
    private ReleaseKnowledgeSnapshots snapshots;

    @BeforeEach
    void setUp() {
        batchMapper = mock(StoryKnowledgeExtractionBatchMapper.class);
        generationMapper = mock(ChapterGenerationMapper.class);
        chapterMapper = mock(ChapterMapper.class);
        proseRevisionMapper = mock(ChapterProseRevisionMapper.class);
        workMapper = mock(WorkMapper.class);
        taskMapper = mock(AiTaskMapper.class);
        settingMapper = mock(SettingEntryMapper.class);
        staleMarker = mock(KnowledgeExtractionStaleMarker.class);
        retryMetadataResolver = mock(GenerationRetryMetadataResolver.class);
        candidateMapper = mock(StoryKnowledgeCandidateMapper.class);
        summaryMapper = mock(ChapterSummaryMapper.class);
        foreshadowingMapper = mock(ForeshadowingItemMapper.class);
        eventMapper = mock(ChapterKeyEventMapper.class);
        snapshots = mock(ReleaseKnowledgeSnapshots.class);
        service = new KnowledgeExtractionServiceImpl(
                batchMapper,
                candidateMapper,
                generationMapper,
                chapterMapper,
                proseRevisionMapper,
                workMapper,
                taskMapper,
                settingMapper,
                foreshadowingMapper,
                summaryMapper,
                eventMapper,
                new ObjectMapper(),
                staleMarker,
                retryMetadataResolver, snapshots);
    }

    @Test
    void confirmingUnpublishedRevisionStagesSummaryWithoutWritingCurrentKnowledge() {
        var candidate = stagedSummary();
        candidate.setCandidateStatus("pending");
        candidate.setDecisionResolution(null);
        var request = new com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ConfirmCandidateRequest(
                0, "create", null, null);
        service.confirm(14L, request);
        verify(summaryMapper, org.mockito.Mockito.never()).insert(
                org.mockito.ArgumentMatchers.any(com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity.class));
        verify(summaryMapper, org.mockito.Mockito.never()).updateById(
                org.mockito.ArgumentMatchers.any(com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity.class));
        verifyNoInteractions(settingMapper, eventMapper, foreshadowingMapper, snapshots);
        var capture = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(candidateMapper).update(org.mockito.ArgumentMatchers.isNull(), capture.capture());
        assertThat(((com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?>) capture.getValue()).getSqlSet())
                .contains("decision_resolution", "decision_target_version").doesNotContain("confirmed_target_id");
    }

    @Test
    void releaseWritesStagedSummaryOnlyBetweenBaselineAndNewSnapshots() {
        stagedSummary();
        service.activateReleaseKnowledge(1L, 12L, 9L, null);
        var order = org.mockito.Mockito.inOrder(snapshots, summaryMapper);
        order.verify(snapshots).capture(1L, 9L, true);
        order.verify(summaryMapper).insert(
                org.mockito.ArgumentMatchers.any(com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity.class));
        order.verify(snapshots).capture(1L, 12L, false);
    }

    @Test
    void releaseRejectsChangedSummaryTargetBeforeWriting() {
        var candidate = stagedSummary();
        candidate.setDecisionResolution("replace");
        candidate.setDecisionTargetId(22L);
        candidate.setDecisionTargetVersion(1);
        var target = new com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity();
        target.setId(22L);
        target.setVersion(2);
        when(summaryMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(target);
        assertThatThrownBy(() -> service.activateReleaseKnowledge(1L, 12L, 9L, null))
                .isInstanceOf(BusinessException.class);
        verify(summaryMapper, org.mockito.Mockito.never()).updateById(
                org.mockito.ArgumentMatchers.any(com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity.class));
        verify(snapshots, org.mockito.Mockito.never()).capture(1L, 12L, false);
    }

    @Test
    void rollbackRestoresSnapshotWithoutReapplyingCandidateDecisions() {
        service.activateReleaseKnowledge(1L, 12L, 9L, 7L);
        var order = org.mockito.Mockito.inOrder(snapshots);
        order.verify(snapshots).capture(1L, 9L, true);
        order.verify(snapshots).restore(1L, 7L);
        order.verify(snapshots).capture(1L, 12L, false);
        verifyNoInteractions(candidateMapper, summaryMapper, settingMapper, eventMapper, foreshadowingMapper);
    }

    private com.dugnan.moqi.knowledge.entity.StoryKnowledgeCandidateEntity stagedSummary() {
        ChapterProseRevisionEntity revision = revision("待发布正文", 10L, "confirmable");
        StoryKnowledgeExtractionBatchEntity batch = batch("待发布正文", 2);
        batch.setSourceProseRevisionId(10L);
        batch.setSourceStoryReleaseId(9L);
        batch.setSourceFingerprint(revisionFingerprint(revision));
        batch.setBatchStatus("ready");
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(workMapper.selectByIdForUpdate(1L)).thenReturn(work(9L));
        when(proseRevisionMapper.selectByIdForUpdate(10L)).thenReturn(revision);
        when(batchMapper.forRelease(1L, 12L, 9L)).thenReturn(List.of(batch));
        var candidate = new com.dugnan.moqi.knowledge.entity.StoryKnowledgeCandidateEntity();
        candidate.setId(14L);
        candidate.setWorkId(1L);
        candidate.setChapterId(5L);
        candidate.setBatchId(9L);
        candidate.setCandidateType("chapter_summary");
        candidate.setCandidateStatus("confirmed");
        candidate.setDecisionResolution("create");
        candidate.setPayloadJson("{\"summary\":\"新摘要\",\"characterChanges\":[],\"openQuestions\":[]}");
        candidate.setDeleted(0);
        candidate.setVersion(0);
        when(candidateMapper.selectById(14L)).thenReturn(candidate);
        when(candidateMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(candidate));
        when(candidateMapper.update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        return candidate;
    }

    @Test
    void startsRevisionExtractionWithoutRequiringAcceptedGeneration() {
        ChapterProseRevisionEntity revision = revision("待发布正文", 10L, "confirmable");
        ChapterGenerationEntity generation = acceptedGeneration();
        generation.setGenerationStatus("succeeded");
        generation.setGeneratedContent("生成候选正文");
        WorkEntity work = work(9L);
        when(workMapper.selectByIdForUpdate(1L)).thenReturn(work);
        when(chapterMapper.selectById(5L)).thenReturn(chapter("当前发布正文", 4));
        when(proseRevisionMapper.selectById(10L)).thenReturn(revision);
        when(generationMapper.selectById(7L)).thenReturn(generation);

        AtomicReference<StoryKnowledgeExtractionBatchEntity> inserted = new AtomicReference<>();
        when(batchMapper.insert(org.mockito.ArgumentMatchers.any(
                StoryKnowledgeExtractionBatchEntity.class))).thenAnswer(invocation -> {
            StoryKnowledgeExtractionBatchEntity batch = invocation.getArgument(0);
            batch.setId(9L);
            inserted.set(batch);
            return 1;
        });
        when(batchMapper.selectById(9L)).thenAnswer(invocation -> inserted.get());
        when(taskMapper.insert(org.mockito.ArgumentMatchers.any(
                com.dugnan.moqi.chapter.entity.AiTaskEntity.class))).thenAnswer(invocation -> {
            com.dugnan.moqi.chapter.entity.AiTaskEntity task = invocation.getArgument(0);
            task.setId(3L);
            return 1;
        });
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.start(org.mockito.ArgumentMatchers.any())).thenReturn(new AgentRunView(
                4L, KnowledgeExtractionServiceImpl.WORKFLOW_TYPE, "queued", 1L, 5L,
                3L, "precheck", 0L, null, null, null, null, null));
        service.setAgentRuntime(runtime);

        var result = service.startRevision(
                1L, 5L, 10L,
                new com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.StartExtractionRequest("rev-key"));

        assertThat(result.sourceProseRevisionId()).isEqualTo(10L);
        assertThat(result.sourceStoryReleaseId()).isEqualTo(9L);
        assertThat(result.generationId()).isEqualTo(7L);
        assertThat(inserted.get().getSourceContent()).isEqualTo("待发布正文");
        assertThat(inserted.get().getSourceContentRevision()).isEqualTo(2);
    }

    @Test
    void marksRevisionBatchStaleWhenReleaseBaselineChanges() {
        ChapterProseRevisionEntity revision = revision("待发布正文", 10L, "confirmable");
        StoryKnowledgeExtractionBatchEntity batch = batch("待发布正文", 2);
        batch.setSourceProseRevisionId(10L);
        batch.setSourceStoryReleaseId(9L);
        batch.setSourceFingerprint(revisionFingerprint(revision));
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(proseRevisionMapper.selectById(10L)).thenReturn(revision);
        when(workMapper.selectById(1L)).thenReturn(work(11L));

        assertThatThrownBy(() -> service.sourceContent(9L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_STALE);
        verify(staleMarker).mark(9L);
    }

    @Test
    void acceptsStructuredOutputBoundToExactAcceptedContent() {
        stubCurrentSource("夜雨停了。", 3);
        ExtractionOutput output = new ExtractionOutput(1, List.of(
                new ExtractedCandidate(
                        "summary-1",
                        "chapter_summary",
                        summaryPayload("夜雨停了。"),
                        new Evidence(0, 5, "夜雨停了。"))));

        ExtractionOutput validated = service.validateOutput(9L, output);

        assertThat(validated.schemaVersion()).isEqualTo(1);
        assertThat(validated.candidates()).hasSize(1);
    }

    @Test
    void rejectsEvidenceThatDoesNotMatchAcceptedContent() {
        stubCurrentSource("夜雨停了。", 3);
        ExtractionOutput output = new ExtractionOutput(1, List.of(
                new ExtractedCandidate(
                        "summary-1",
                        "chapter_summary",
                        summaryPayload("夜雨停了。"),
                        new Evidence(0, 2, "错误"))));

        assertThatThrownBy(() -> service.validateOutput(9L, output))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_INVALID);
    }

    @Test
    void normalizesUniqueExactEvidenceToJavaOffsets() {
        stubCurrentSource("雨停后，林澈抵达码头。", 3);
        ExtractionOutput output = new ExtractionOutput(1, List.of(
                new ExtractedCandidate(
                        "summary-1",
                        "chapter_summary",
                        summaryPayload("林澈抵达码头。"),
                        new Evidence(0, 99, "林澈抵达码头。"))));

        ExtractionOutput validated = service.validateOutput(9L, output);

        assertThat(validated.candidates().get(0).evidence())
                .isEqualTo(new Evidence(4, 11, "林澈抵达码头。"));
    }

    @Test
    void rejectsAmbiguousEvidenceInsteadOfGuessingOffsets() {
        stubCurrentSource("钟声响起，钟声响起。", 3);
        ExtractionOutput output = new ExtractionOutput(1, List.of(
                new ExtractedCandidate(
                        "summary-1",
                        "chapter_summary",
                        summaryPayload("钟声响起。"),
                        new Evidence(0, 99, "钟声响起"))));

        assertThatThrownBy(() -> service.validateOutput(9L, output))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_INVALID);
    }

    @Test
    void rejectsProviderPayloadThatOmitsRequiredArrays() {
        stubCurrentSource("夜雨停了。", 3);
        ExtractionOutput output = new ExtractionOutput(1, List.of(new ExtractedCandidate(
                "summary-1", "chapter_summary", Map.of("summary", "夜雨停了。"),
                new Evidence(0, 5, "夜雨停了。"))));

        assertThatThrownBy(() -> service.validateOutput(9L, output))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_INVALID);
    }

    @Test
    void rejectsFractionalProviderIntegerAndNonSeedForeshadowing() {
        stubCurrentSource("夜雨停了。", 3);
        ExtractedCandidate summary = new ExtractedCandidate(
                "summary-1", "chapter_summary", summaryPayload("夜雨停了。"),
                new Evidence(0, 5, "夜雨停了。"));
        ExtractedCandidate event = new ExtractedCandidate(
                "event-1", "key_event", Map.of(
                        "title", "雨停", "content", "夜雨停了。", "eventType", "plot",
                        "occurredOrder", 1.5, "relatedSettingIds", List.of(),
                        "relatedForeshadowingIds", List.of()),
                new Evidence(0, 5, "夜雨停了。"));
        ExtractedCandidate foreshadowing = new ExtractedCandidate(
                "foreshadowing-1", "foreshadowing", Map.of(
                        "action", "advance", "title", "雨声", "description", "雨声推进伏笔"),
                new Evidence(0, 5, "夜雨停了。"));

        assertThatThrownBy(() -> service.validateOutput(9L, new ExtractionOutput(1, List.of(summary, event))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_INVALID);
        assertThatThrownBy(() -> service.validateOutput(
                9L, new ExtractionOutput(1, List.of(summary, foreshadowing))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_INVALID);
    }

    @Test
    void generationDetailRejectsRevisionBatchWithSameGenerationId() {
        StoryKnowledgeExtractionBatchEntity revisionBatch = batch("待发布正文", 2);
        revisionBatch.setSourceProseRevisionId(10L);
        when(batchMapper.selectById(9L)).thenReturn(revisionBatch);

        assertThatThrownBy(() -> service.get(5L, 7L, 9L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_NOT_FOUND);
    }

    @Test
    void revisionRetryLocksWorkAndRevisionBeforeCallingAgentRuntime() {
        ChapterProseRevisionEntity revision = revision("待发布正文", 10L, "confirmable");
        StoryKnowledgeExtractionBatchEntity batch = batch("待发布正文", 2);
        batch.setSourceProseRevisionId(10L);
        batch.setSourceStoryReleaseId(9L);
        batch.setSourceFingerprint(revisionFingerprint(revision));
        batch.setAgentRunId(4L);
        batch.setBatchStatus("failed");
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(workMapper.selectByIdForUpdate(1L)).thenReturn(work(9L));
        when(proseRevisionMapper.selectByIdForUpdate(10L)).thenReturn(revision);
        when(retryMetadataResolver.resolveOwned(
                4L, "extract", KnowledgeExtractionServiceImpl.WORKFLOW_TYPE, 1L, 5L, null))
                .thenReturn(new RetryMetadata("extract", 2, true));
        when(batchMapper.update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        AgentRuntime runtime = mock(AgentRuntime.class);
        service.setAgentRuntime(runtime);

        service.retryRevision(1L, 5L, 10L, 9L,
                new com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.RetryExtractionRequest(2));

        verify(workMapper).selectByIdForUpdate(1L);
        verify(proseRevisionMapper).selectByIdForUpdate(10L);
        verify(runtime).retryStep(new com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand(
                4L, "extract", 2));
    }

    @Test
    void rejectsStaleRevisionRetryAttemptBeforeChangingBatchOrCallingRuntime() {
        ChapterProseRevisionEntity revision = revision("待发布正文", 10L, "confirmable");
        StoryKnowledgeExtractionBatchEntity batch = batch("待发布正文", 2);
        batch.setSourceProseRevisionId(10L);
        batch.setSourceStoryReleaseId(9L);
        batch.setSourceFingerprint(revisionFingerprint(revision));
        batch.setAgentRunId(4L);
        batch.setBatchStatus("failed");
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(workMapper.selectByIdForUpdate(1L)).thenReturn(work(9L));
        when(proseRevisionMapper.selectByIdForUpdate(10L)).thenReturn(revision);
        when(retryMetadataResolver.resolveOwned(
                4L, "extract", KnowledgeExtractionServiceImpl.WORKFLOW_TYPE, 1L, 5L, null))
                .thenReturn(new RetryMetadata("extract", 3, true));
        AgentRuntime runtime = mock(AgentRuntime.class);
        service.setAgentRuntime(runtime);

        assertThatThrownBy(() -> service.retryRevision(1L, 5L, 10L, 9L,
                new com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.RetryExtractionRequest(2)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_CONFLICT);

        verifyNoInteractions(runtime);
    }

    @Test
    void rejectsRevisionRetryWhenBatchCasLosesWithoutCallingRuntime() {
        ChapterProseRevisionEntity revision = revision("待发布正文", 10L, "confirmable");
        StoryKnowledgeExtractionBatchEntity batch = batch("待发布正文", 2);
        batch.setSourceProseRevisionId(10L);
        batch.setSourceStoryReleaseId(9L);
        batch.setSourceFingerprint(revisionFingerprint(revision));
        batch.setAgentRunId(4L);
        batch.setBatchStatus("failed");
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(workMapper.selectByIdForUpdate(1L)).thenReturn(work(9L));
        when(proseRevisionMapper.selectByIdForUpdate(10L)).thenReturn(revision);
        when(retryMetadataResolver.resolveOwned(
                4L, "extract", KnowledgeExtractionServiceImpl.WORKFLOW_TYPE, 1L, 5L, null))
                .thenReturn(new RetryMetadata("extract", 2, true));
        when(batchMapper.update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(0);
        AgentRuntime runtime = mock(AgentRuntime.class);
        service.setAgentRuntime(runtime);

        assertThatThrownBy(() -> service.retryRevision(1L, 5L, 10L, 9L,
                new com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.RetryExtractionRequest(2)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_CONFLICT);

        verifyNoInteractions(runtime);
    }

    @Test
    void exposesLatestRetryAttemptForCurrentFailedRevisionBatch() {
        ChapterProseRevisionEntity revision = revision("待发布正文", 10L, "confirmable");
        StoryKnowledgeExtractionBatchEntity batch = batch("待发布正文", 2);
        batch.setBatchStatus("failed");
        batch.setSourceProseRevisionId(10L);
        batch.setSourceStoryReleaseId(9L);
        batch.setSourceFingerprint(revisionFingerprint(revision));
        batch.setAgentRunId(4L);
        batch.setAiTaskId(3L);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(workMapper.selectById(1L)).thenReturn(work(9L));
        when(proseRevisionMapper.selectById(10L)).thenReturn(revision);
        when(retryMetadataResolver.resolveOwned(
                4L, "extract", KnowledgeExtractionServiceImpl.WORKFLOW_TYPE, 1L, 5L, 3L))
                .thenReturn(new RetryMetadata("extract", 2, true));

        var result = service.getRevision(1L, 5L, 10L, 9L);

        assertThat(result.currentAttempt()).isEqualTo(2);
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void keepsAttemptButDisablesRetryWhenRevisionSourceIsStale() {
        ChapterProseRevisionEntity revision = revision("待发布正文", 10L, "confirmable");
        StoryKnowledgeExtractionBatchEntity batch = batch("待发布正文", 2);
        batch.setBatchStatus("failed");
        batch.setSourceProseRevisionId(10L);
        batch.setSourceStoryReleaseId(9L);
        batch.setSourceFingerprint(revisionFingerprint(revision));
        batch.setAgentRunId(4L);
        batch.setAiTaskId(3L);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(workMapper.selectById(1L)).thenReturn(work(11L));
        when(proseRevisionMapper.selectById(10L)).thenReturn(revision);
        when(retryMetadataResolver.resolveOwned(
                4L, "extract", KnowledgeExtractionServiceImpl.WORKFLOW_TYPE, 1L, 5L, 3L))
                .thenReturn(new RetryMetadata("extract", 3, true));

        var result = service.getRevision(1L, 5L, 10L, 9L);

        assertThat(result.currentAttempt()).isEqualTo(3);
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void disablesRetryWhenRuntimeIsNotOnExtractStep() {
        ChapterProseRevisionEntity revision = revision("待发布正文", 10L, "confirmable");
        StoryKnowledgeExtractionBatchEntity batch = batch("待发布正文", 2);
        batch.setBatchStatus("failed");
        batch.setSourceProseRevisionId(10L);
        batch.setSourceStoryReleaseId(9L);
        batch.setSourceFingerprint(revisionFingerprint(revision));
        batch.setAgentRunId(4L);
        batch.setAiTaskId(3L);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(retryMetadataResolver.resolveOwned(
                4L, "extract", KnowledgeExtractionServiceImpl.WORKFLOW_TYPE, 1L, 5L, 3L))
                .thenReturn(new RetryMetadata("persist", 2, true));

        var result = service.getRevision(1L, 5L, 10L, 9L);

        assertThat(result.currentAttempt()).isEqualTo(2);
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void exposesRetryMetadataForCurrentAcceptedGenerationBatch() {
        StoryKnowledgeExtractionBatchEntity batch = batch("夜雨停了。", 3);
        batch.setBatchStatus("failed");
        batch.setAgentRunId(4L);
        batch.setAiTaskId(3L);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(generationMapper.selectById(7L)).thenReturn(acceptedGeneration());
        when(chapterMapper.selectById(5L)).thenReturn(chapter("夜雨停了。", 3));
        when(retryMetadataResolver.resolveOwned(
                4L, "extract", KnowledgeExtractionServiceImpl.WORKFLOW_TYPE, 1L, 5L, 3L))
                .thenReturn(new RetryMetadata("extract", 3, true));

        var result = service.get(5L, 7L, 9L);

        assertThat(result.currentAttempt()).isEqualTo(3);
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void rejectsCrossWorkSettingReference() {
        stubCurrentSource("他们抵达钟楼。", 3);
        SettingEntryEntity foreign = new SettingEntryEntity();
        foreign.setId(88L);
        foreign.setWorkId(2L);
        foreign.setEntryStatus("active");
        foreign.setDeleted(0);
        when(settingMapper.selectById(88L)).thenReturn(foreign);
        ExtractionOutput output = new ExtractionOutput(1, List.of(
                new ExtractedCandidate(
                        "summary-1",
                        "chapter_summary",
                        summaryPayload("他们抵达钟楼。"),
                        new Evidence(0, 7, "他们抵达钟楼。")),
                new ExtractedCandidate(
                        "event-1",
                        "key_event",
                        Map.of(
                                "title", "抵达",
                                "content", "他们抵达钟楼。",
                                "eventType", "plot",
                                "occurredOrder", 1,
                                "relatedSettingIds", List.of(88L),
                                "relatedForeshadowingIds", List.of()),
                        new Evidence(0, 7, "他们抵达钟楼。"))));

        assertThatThrownBy(() -> service.validateOutput(9L, output))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_INVALID);
    }

    @Test
    void marksBatchStaleWhenAcceptedContentChanges() {
        StoryKnowledgeExtractionBatchEntity batch = batch("原文", 3);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ChapterGenerationEntity generation = acceptedGeneration();
        when(generationMapper.selectById(7L)).thenReturn(generation);
        ChapterEntity chapter = chapter("改写后的正文", 4);
        when(chapterMapper.selectById(5L)).thenReturn(chapter);

        assertThatThrownBy(() -> service.sourceContent(9L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.KNOWLEDGE_EXTRACTION_STALE);
        verify(staleMarker).mark(9L);
    }

    @Test
    void rejectsGenerationThatHasNotBeenAccepted() {
        ChapterGenerationEntity generation = acceptedGeneration();
        generation.setGenerationStatus("succeeded");
        when(generationMapper.selectById(7L)).thenReturn(generation);

        assertThatThrownBy(() -> service.startAcceptedGeneration(7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GENERATION_STATUS_CONFLICT);
    }

    private void stubCurrentSource(String content, int revision) {
        StoryKnowledgeExtractionBatchEntity batch = batch(content, revision);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(generationMapper.selectById(7L)).thenReturn(acceptedGeneration());
        when(chapterMapper.selectById(5L)).thenReturn(chapter(content, revision));
    }

    private StoryKnowledgeExtractionBatchEntity batch(String content, int revision) {
        StoryKnowledgeExtractionBatchEntity batch = new StoryKnowledgeExtractionBatchEntity();
        batch.setId(9L);
        batch.setWorkId(1L);
        batch.setChapterId(5L);
        batch.setGenerationId(7L);
        batch.setSourceContent(content);
        batch.setSourceContentRevision(revision);
        batch.setSourceFingerprint(sourceFingerprint(7L, revision, content));
        batch.setBatchStatus("running");
        batch.setDeleted(0);
        batch.setVersion(0);
        return batch;
    }

    private ChapterGenerationEntity acceptedGeneration() {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(7L);
        generation.setWorkId(1L);
        generation.setChapterId(5L);
        generation.setGenerationStatus("accepted");
        generation.setGeneratedContent("夜雨停了。");
        generation.setDeleted(0);
        return generation;
    }

    private Map<String, Object> summaryPayload(String summary) {
        return Map.of("summary", summary, "characterChanges", List.of(), "openQuestions", List.of());
    }

    private ChapterProseRevisionEntity revision(String content, Long id, String status) {
        ChapterProseRevisionEntity revision = new ChapterProseRevisionEntity();
        revision.setId(id);
        revision.setWorkId(1L);
        revision.setChapterId(5L);
        revision.setSourceGenerationId(7L);
        revision.setRevisionNo(2);
        revision.setRevisionStatus(status);
        revision.setContent(content);
        revision.setContentHash(hash(content));
        revision.setDeleted(0);
        revision.setVersion(0);
        return revision;
    }

    private WorkEntity work(Long currentReleaseId) {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setCurrentStoryReleaseId(currentReleaseId);
        work.setDeleted(0);
        work.setVersion(3);
        return work;
    }

    private ChapterEntity chapter(String content, int revision) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(5L);
        chapter.setWorkId(1L);
        chapter.setContent(content);
        chapter.setVersion(revision);
        chapter.setDeleted(0);
        return chapter;
    }

    private String sourceFingerprint(Long generationId, int revision, String content) {
        try {
            var method = KnowledgeExtractionServiceImpl.class.getDeclaredMethod(
                    "fingerprint", Long.class, Integer.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(service, generationId, revision, content);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private String revisionFingerprint(ChapterProseRevisionEntity revision) {
        try {
            var method = KnowledgeExtractionServiceImpl.class.getDeclaredMethod(
                    "revisionFingerprint", ChapterProseRevisionEntity.class);
            method.setAccessible(true);
            return (String) method.invoke(service, revision);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private String hash(String content) {
        try {
            var method = KnowledgeExtractionServiceImpl.class.getDeclaredMethod("hash", String.class);
            method.setAccessible(true);
            return (String) method.invoke(service, content);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
