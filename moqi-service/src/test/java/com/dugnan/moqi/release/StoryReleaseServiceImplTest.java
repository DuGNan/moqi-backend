package com.dugnan.moqi.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.entity.BoundedChapterRevisionEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.BoundedChapterRevisionMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.release.StoryReleaseModels.AbandonRevisionRequest;
import com.dugnan.moqi.release.StoryReleaseModels.CreateRevisionRequest;
import com.dugnan.moqi.release.StoryReleaseModels.PrepareWorkspaceRequest;
import com.dugnan.moqi.release.StoryReleaseModels.PublishWorkspaceRequest;
import com.dugnan.moqi.release.StoryReleaseModels.RollbackReleaseRequest;
import com.dugnan.moqi.release.entity.ChapterProseRevisionEntity;
import com.dugnan.moqi.release.entity.StoryReleaseChapterEntity;
import com.dugnan.moqi.release.entity.StoryReleaseEntity;
import com.dugnan.moqi.release.entity.WorkRevisionWorkspaceEntity;
import com.dugnan.moqi.release.mapper.ChapterProseRevisionMapper;
import com.dugnan.moqi.release.mapper.StoryReleaseChapterMapper;
import com.dugnan.moqi.release.mapper.StoryReleaseMapper;
import com.dugnan.moqi.release.mapper.WorkRevisionWorkspaceChapterMapper;
import com.dugnan.moqi.release.mapper.WorkRevisionWorkspaceMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证正文 revision 和 Story Release 的候选、哈希、版本及人工确认边界。
 */
class StoryReleaseServiceImplTest {

    @Test
    void createsImmutableDraftFromCurrentPublishedParent() {
        Fixture fixture = new Fixture();
        fixture.chapter.setCurrentProseRevisionId(5L);
        ChapterProseRevisionEntity parent = fixture.revision(5L, "published", "旧正文");
        when(fixture.proseRevisionMapper.selectById(5L)).thenReturn(parent);
        when(fixture.proseRevisionMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            ChapterProseRevisionEntity item = invocation.getArgument(0);
            item.setId(6L);
            return 1;
        }).when(fixture.proseRevisionMapper).insert(any(ChapterProseRevisionEntity.class));

        var view = fixture.service.createRevision(1L, 2L,
                new CreateRevisionRequest(5L, null, null, "新正文", "revision-1"));

        assertThat(view.id()).isEqualTo(6L);
        assertThat(view.parentRevisionId()).isEqualTo(5L);
        assertThat(view.revisionStatus()).isEqualTo("draft");
        assertThat(view.revisionOrigin()).isEqualTo("manual");
        assertThat(fixture.chapter.getContent()).isEqualTo("已发布正文");
    }

    @Test
    void rejectsDraftWhoseParentIsNotCurrentPublishedRevision() {
        Fixture fixture = new Fixture();
        fixture.chapter.setCurrentProseRevisionId(5L);

        assertThatThrownBy(() -> fixture.service.createRevision(1L, 2L,
                new CreateRevisionRequest(4L, null, null, "新正文", "revision-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前发布 revision");
    }

    @Test
    void idempotentRevisionRetrySurvivesLaterPublishedPointerChanges() {
        Fixture fixture = new Fixture();
        fixture.chapter.setCurrentProseRevisionId(9L);
        ChapterProseRevisionEntity existing = fixture.revision(6L, "draft", "新正文");
        existing.setParentRevisionId(5L);
        existing.setIdempotencyKey("revision-1");
        when(fixture.proseRevisionMapper.selectOne(any())).thenReturn(existing);

        var view = fixture.service.createRevision(1L, 2L,
                new CreateRevisionRequest(5L, null, null, "新正文", "revision-1"));

        assertThat(view.id()).isEqualTo(6L);
    }

    @Test
    void rejectsEvaluationWhoseHashDoesNotMatchRevision() {
        Fixture fixture = new Fixture();
        ChapterProseRevisionEntity revision = fixture.revision(6L, "draft", "正文 A");
        ChapterGenerationEvaluationReportEntity report = new ChapterGenerationEvaluationReportEntity();
        report.setId(9L);
        report.setWorkId(1L);
        report.setChapterId(2L);
        report.setContentHash("different");
        report.setDeleted(0);
        when(fixture.proseRevisionMapper.selectById(6L)).thenReturn(revision);
        when(fixture.evaluationReportMapper.selectById(9L)).thenReturn(report);

        assertThatThrownBy(() -> fixture.service.bindEvaluation(
                1L, 2L, 6L, new StoryReleaseModels.BindEvaluationRequest(9L, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("哈希");
    }

    @Test
    void rejectsSameHashReportFromDifferentGeneration() {
        Fixture fixture = new Fixture();
        ChapterProseRevisionEntity revision = fixture.revision(6L, "draft", "相同正文");
        revision.setSourceGenerationId(60L);
        ChapterGenerationEvaluationReportEntity report = fixture.report(9L, 61L, revision.getContentHash());
        when(fixture.proseRevisionMapper.selectById(6L)).thenReturn(revision);
        when(fixture.evaluationReportMapper.selectById(9L)).thenReturn(report);

        assertThatThrownBy(() -> fixture.service.bindEvaluation(
                1L, 2L, 6L, new StoryReleaseModels.BindEvaluationRequest(9L, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("generation");
    }

    @Test
    void rejectsEvaluationBindingForManualRevisionWithoutSourceGeneration() {
        Fixture fixture = new Fixture();
        ChapterProseRevisionEntity revision = fixture.revision(6L, "draft", "手工正文");
        ChapterGenerationEvaluationReportEntity report = fixture.report(9L, 60L, revision.getContentHash());
        when(fixture.proseRevisionMapper.selectById(6L)).thenReturn(revision);
        when(fixture.evaluationReportMapper.selectById(9L)).thenReturn(report);

        assertThatThrownBy(() -> fixture.service.bindEvaluation(
                1L, 2L, 6L, new StoryReleaseModels.BindEvaluationRequest(9L, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("generation");
    }

    @Test
    void bindsEvaluationFromExactSourceGeneration() {
        Fixture fixture = new Fixture();
        ChapterProseRevisionEntity revision = fixture.revision(6L, "draft", "正文");
        revision.setSourceGenerationId(60L);
        ChapterGenerationEvaluationReportEntity report = fixture.report(9L, 60L, revision.getContentHash());
        when(fixture.proseRevisionMapper.selectById(6L)).thenReturn(revision);
        when(fixture.evaluationReportMapper.selectById(9L)).thenReturn(report);
        doAnswer(invocation -> {
            revision.setEvaluationReportId(9L);
            revision.setRevisionStatus("confirmable");
            revision.setVersion(1);
            return 1;
        }).when(fixture.proseRevisionMapper).update(any(), any());

        var view = fixture.service.bindEvaluation(
                1L, 2L, 6L, new StoryReleaseModels.BindEvaluationRequest(9L, 0));

        assertThat(view.revisionStatus()).isEqualTo("confirmable");
        assertThat(view.evaluationReportId()).isEqualTo(9L);
        verify(fixture.evaluationService).requireAdoptable(2L, 60L);
    }

    @Test
    void boundedRevisionBindsReportAfterReevaluationBecomesLogicallyCandidateReady() {
        Fixture fixture = new Fixture();
        ChapterGenerationEntity generation = fixture.generation(60L, "#106 新候选");
        generation.setContentAssemblyMode("bounded_revision");
        BoundedChapterRevisionEntity bounded = fixture.boundedRevision(30L, 60L);
        bounded.setRevisionStatus("re_evaluating");
        bounded.setResultReportId(9L);
        ChapterGenerationEvaluationReportEntity report = fixture.report(
                9L, 60L, fixture.sha256("#106 新候选"));
        when(fixture.generationMapper.selectById(60L)).thenReturn(generation);
        when(fixture.boundedRevisionMapper.selectById(30L)).thenReturn(bounded);
        when(fixture.evaluationReportMapper.selectById(9L)).thenReturn(report);
        when(fixture.proseRevisionMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            ChapterProseRevisionEntity item = invocation.getArgument(0);
            item.setId(6L);
            return 1;
        }).when(fixture.proseRevisionMapper).insert(any(ChapterProseRevisionEntity.class));

        var created = fixture.service.createRevision(1L, 2L,
                new CreateRevisionRequest(null, 60L, 30L, null, "bounded-revision"));
        ChapterProseRevisionEntity revision = fixture.revision(6L, "draft", "#106 新候选");
        revision.setSourceGenerationId(60L);
        revision.setSourceBoundedRevisionId(30L);
        when(fixture.proseRevisionMapper.selectById(6L)).thenReturn(revision);
        doAnswer(invocation -> {
            revision.setEvaluationReportId(9L);
            revision.setRevisionStatus("confirmable");
            revision.setVersion(1);
            return 1;
        }).when(fixture.proseRevisionMapper).update(any(), any());

        var bound = fixture.service.bindEvaluation(
                1L, 2L, 6L, new StoryReleaseModels.BindEvaluationRequest(9L, 0));

        assertThat(created.revisionOrigin()).isEqualTo("bounded_revision");
        assertThat(bound.revisionStatus()).isEqualTo("confirmable");
        verify(fixture.evaluationService).requireAdoptable(2L, 60L);
    }

    @Test
    void rejectsBoundedGenerationWithoutBoundedRevisionLink() {
        Fixture fixture = new Fixture();
        ChapterGenerationEntity generation = fixture.generation(60L, "#106 新候选");
        generation.setContentAssemblyMode("bounded_revision");
        when(fixture.generationMapper.selectById(60L)).thenReturn(generation);
        when(fixture.proseRevisionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> fixture.service.createRevision(1L, 2L,
                new CreateRevisionRequest(null, 60L, null, null, "bounded-without-task")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sourceBoundedRevisionId");
    }

    @Test
    void rejectsBoundedRevisionTaskThatIsNotCandidateReady() {
        Fixture fixture = new Fixture();
        ChapterGenerationEntity generation = fixture.generation(60L, "#106 新候选");
        generation.setContentAssemblyMode("bounded_revision");
        BoundedChapterRevisionEntity bounded = fixture.boundedRevision(30L, 60L);
        bounded.setRevisionStatus("needs_human");
        bounded.setResultReportId(9L);
        when(fixture.generationMapper.selectById(60L)).thenReturn(generation);
        when(fixture.boundedRevisionMapper.selectById(30L)).thenReturn(bounded);
        when(fixture.proseRevisionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> fixture.service.createRevision(1L, 2L,
                new CreateRevisionRequest(null, 60L, 30L, null, "bounded-needs-human")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("candidate_ready");
    }

    @Test
    void rejectsReevaluatingBoundedRevisionBeforeResultReportIsReady() {
        Fixture fixture = new Fixture();
        ChapterGenerationEntity generation = fixture.generation(60L, "#106 新候选");
        generation.setContentAssemblyMode("bounded_revision");
        BoundedChapterRevisionEntity bounded = fixture.boundedRevision(30L, 60L);
        bounded.setRevisionStatus("re_evaluating");
        bounded.setResultReportId(9L);
        ChapterGenerationEvaluationReportEntity report = fixture.report(
                9L, 60L, fixture.sha256("#106 新候选"));
        report.setReportStatus("running");
        report.setConclusion(null);
        when(fixture.generationMapper.selectById(60L)).thenReturn(generation);
        when(fixture.boundedRevisionMapper.selectById(30L)).thenReturn(bounded);
        when(fixture.evaluationReportMapper.selectById(9L)).thenReturn(report);
        when(fixture.proseRevisionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> fixture.service.createRevision(1L, 2L,
                new CreateRevisionRequest(null, 60L, 30L, null, "bounded-still-re-evaluating")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("candidate_ready");
    }

    @Test
    void rejectsBoundedRevisionReportDifferentFromTaskResultReport() {
        Fixture fixture = new Fixture();
        ChapterProseRevisionEntity revision = fixture.revision(6L, "draft", "#106 新候选");
        revision.setSourceGenerationId(60L);
        revision.setSourceBoundedRevisionId(30L);
        BoundedChapterRevisionEntity bounded = fixture.boundedRevision(30L, 60L);
        bounded.setRevisionStatus("candidate_ready");
        bounded.setResultReportId(10L);
        ChapterGenerationEvaluationReportEntity report = fixture.report(9L, 60L, revision.getContentHash());
        when(fixture.proseRevisionMapper.selectById(6L)).thenReturn(revision);
        when(fixture.boundedRevisionMapper.selectById(30L)).thenReturn(bounded);
        when(fixture.evaluationReportMapper.selectById(9L)).thenReturn(report);

        assertThatThrownBy(() -> fixture.service.bindEvaluation(
                1L, 2L, 6L, new StoryReleaseModels.BindEvaluationRequest(9L, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("resultReportId");
    }

    @Test
    void abandonOnlyChangesCandidateStateAndNeverChapterContent() {
        Fixture fixture = new Fixture();
        ChapterProseRevisionEntity draft = fixture.revision(6L, "draft", "候选正文");
        ChapterProseRevisionEntity abandoned = fixture.revision(6L, "abandoned", "候选正文");
        abandoned.setVersion(1);
        when(fixture.proseRevisionMapper.selectById(6L)).thenReturn(draft, abandoned);
        when(fixture.proseRevisionMapper.update(any(), any())).thenReturn(1);

        var view = fixture.service.abandonRevision(1L, 2L, 6L, new AbandonRevisionRequest(0));

        assertThat(view.revisionStatus()).isEqualTo("abandoned");
        assertThat(fixture.chapter.getContent()).isEqualTo("已发布正文");
        verify(fixture.proseRevisionMapper).update(any(), any());
    }

    @Test
    void emptyWorkspaceStaysDraftWithBlockingEvidence() {
        Fixture fixture = new Fixture();
        WorkRevisionWorkspaceEntity draft = fixture.workspace("draft", 0);
        WorkRevisionWorkspaceEntity blocked = fixture.workspace("draft", 1);
        blocked.setBlockingItemsJson("[\"workspace_has_no_revision\"]");
        when(fixture.workspaceMapper.selectById(10L)).thenReturn(draft, blocked);
        when(fixture.workspaceChapterMapper.selectList(any())).thenReturn(java.util.List.of());
        when(fixture.chapterMapper.selectList(any())).thenReturn(java.util.List.of());
        when(fixture.workspaceMapper.update(any(), any())).thenReturn(1);

        var view = fixture.service.prepareWorkspace(1L, 10L, new PrepareWorkspaceRequest(0));

        assertThat(view.workspaceStatus()).isEqualTo("draft");
        assertThat(view.blockingItems()).containsExactly("workspace_has_no_revision");
    }

    @Test
    void workspaceBlocksExistingChapterMissingFromReleaseBaseline() {
        Fixture fixture = new Fixture();
        WorkRevisionWorkspaceEntity draft = fixture.workspace("draft", 0);
        WorkRevisionWorkspaceEntity blocked = fixture.workspace("draft", 1);
        blocked.setBlockingItemsJson("[\"workspace_has_no_revision\",\"release_missing_chapter:2\"]");
        when(fixture.workspaceMapper.selectById(10L)).thenReturn(draft, blocked);
        when(fixture.workspaceChapterMapper.selectList(any())).thenReturn(java.util.List.of());
        when(fixture.chapterMapper.selectList(any())).thenReturn(java.util.List.of(fixture.chapter));
        when(fixture.workspaceMapper.update(any(), any())).thenReturn(1);

        var view = fixture.service.prepareWorkspace(1L, 10L, new PrepareWorkspaceRequest(0));

        assertThat(view.blockingItems()).contains("release_missing_chapter:2");
    }

    @Test
    void publishingRequiresExplicitUserConfirmation() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.publishWorkspace(1L, 10L,
                new PublishWorkspaceRequest(2, "publish-1", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户显式确认");
    }

    @Test
    void publishIdempotencyKeyCannotReturnReleaseFromAnotherWorkspace() {
        Fixture fixture = new Fixture();
        StoryReleaseEntity repeated = fixture.release(20L, 3L, null);
        when(fixture.storyReleaseMapper.selectOne(any())).thenReturn(repeated);
        WorkRevisionWorkspaceEntity otherWorkspace = fixture.workspace("published", 4);
        otherWorkspace.setId(11L);
        otherWorkspace.setPublishedReleaseId(20L);
        when(fixture.workspaceMapper.selectById(10L)).thenReturn(null);
        when(fixture.workspaceMapper.selectById(11L)).thenReturn(otherWorkspace);

        assertThatThrownBy(() -> fixture.service.publishWorkspace(1L, 10L,
                new PublishWorkspaceRequest(3, "shared-key", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("幂等键");
    }

    @Test
    void publishIdempotencyReplayReturnsReleaseOnlyForOriginalWorkspace() {
        Fixture fixture = new Fixture();
        StoryReleaseEntity repeated = fixture.release(20L, 3L, null);
        WorkRevisionWorkspaceEntity workspace = fixture.workspace("published", 4);
        workspace.setPublishedReleaseId(20L);
        when(fixture.storyReleaseMapper.selectOne(any())).thenReturn(repeated);
        when(fixture.workspaceMapper.selectById(10L)).thenReturn(workspace);

        var view = fixture.service.publishWorkspace(1L, 10L,
                new PublishWorkspaceRequest(3, "publish-key", true));

        assertThat(view.id()).isEqualTo(20L);
    }

    @Test
    void rollbackIdempotencyKeyMustMatchTargetAndParentRelease() {
        Fixture fixture = new Fixture();
        StoryReleaseEntity repeated = fixture.release(20L, 9L, 6L);
        when(fixture.storyReleaseMapper.selectOne(any())).thenReturn(repeated);

        assertThatThrownBy(() -> fixture.service.rollback(1L, 7L,
                new RollbackReleaseRequest(9L, 3, "shared-key", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("幂等键");
    }

    @Test
    void rollbackIdempotencyReplayMatchesTargetAndOriginalParent() {
        Fixture fixture = new Fixture();
        StoryReleaseEntity repeated = fixture.release(20L, 9L, 7L);
        when(fixture.storyReleaseMapper.selectOne(any())).thenReturn(repeated);

        var view = fixture.service.rollback(1L, 7L,
                new RollbackReleaseRequest(9L, 3, "rollback-key", true));

        assertThat(view.id()).isEqualTo(20L);
    }

    @Test
    void rollbackCannotReuseIdempotencyKeyFromPublishOperation() {
        Fixture fixture = new Fixture();
        StoryReleaseEntity published = fixture.release(20L, 9L, null);
        when(fixture.storyReleaseMapper.selectOne(any())).thenReturn(published);

        assertThatThrownBy(() -> fixture.service.rollback(1L, 7L,
                new RollbackReleaseRequest(9L, 3, "publish-key", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("幂等键");
    }

    @Test
    void rollbackRequiresExplicitUserConfirmationAndExpectedCurrentRelease() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.rollback(1L, 7L,
                new RollbackReleaseRequest(null, 3, "rollback-1", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前 release");
    }

    @Test
    void rollbackTreatsTargetAsCompleteSnapshotAndUnpublishesLaterChapter() {
        Fixture fixture = new Fixture();
        fixture.work.setCurrentStoryReleaseId(9L);
        fixture.work.setVersion(3);
        fixture.chapter.setCurrentProseRevisionId(5L);
        ChapterEntity laterChapter = fixture.chapter(3L, 2, "后来新增的公开正文", 2, 6L);
        ChapterProseRevisionEntity targetRevision = fixture.revision(5L, "published", "旧快照正文");
        fixture.chapter.setContent(targetRevision.getContent());

        StoryReleaseEntity targetRelease = fixture.release(7L, null, null);
        StoryReleaseEntity currentRelease = fixture.release(9L, 7L, null);
        currentRelease.setCurrentMarker(1);
        StoryReleaseChapterEntity targetChapter = fixture.mapping(7L, 2L, 5L, 1, targetRevision.getContentHash());
        StoryReleaseChapterEntity currentChapter = fixture.mapping(9L, 2L, 5L, 1, targetRevision.getContentHash());
        StoryReleaseChapterEntity laterMapping = fixture.mapping(
                9L, 3L, 6L, 2, fixture.sha256("后来新增的公开正文"));

        when(fixture.storyReleaseMapper.selectOne(any())).thenReturn(null, currentRelease);
        when(fixture.storyReleaseMapper.selectById(7L)).thenReturn(targetRelease);
        when(fixture.storyReleaseMapper.selectById(9L)).thenReturn(currentRelease);
        when(fixture.releaseChapterMapper.selectList(any())).thenReturn(
                java.util.List.of(currentChapter, laterMapping),
                java.util.List.of(targetChapter),
                java.util.List.of(currentChapter, laterMapping),
                java.util.List.of(targetChapter));
        when(fixture.workMapper.selectByIdForUpdate(1L)).thenReturn(fixture.work);
        when(fixture.chapterMapper.selectByIdForUpdate(2L)).thenReturn(fixture.chapter);
        when(fixture.chapterMapper.selectByIdForUpdate(3L)).thenReturn(laterChapter);
        when(fixture.proseRevisionMapper.selectById(5L)).thenReturn(targetRevision);
        ChapterProseRevisionEntity laterRevision = fixture.revision(6L, "published", "后来新增的公开正文");
        laterRevision.setChapterId(3L);
        when(fixture.proseRevisionMapper.selectById(6L)).thenReturn(laterRevision);
        doAnswer(invocation -> {
            laterChapter.setCurrentProseRevisionId(null);
            laterChapter.setContent(null);
            laterChapter.setVersion(laterChapter.getVersion() + 1);
            return 1;
        }).when(fixture.chapterMapper).clearPublishedRevisionIfVersion(3L, 2, 6L);
        when(fixture.workMapper.updateCurrentStoryReleaseIfVersion(1L, 10L, 3, 9L)).thenReturn(1);
        when(fixture.storyReleaseMapper.update(any(), any())).thenReturn(1);
        when(fixture.proseRevisionMapper.update(any(), any())).thenReturn(1);
        doAnswer(invocation -> {
            StoryReleaseEntity item = invocation.getArgument(0);
            item.setId(10L);
            return 1;
        }).when(fixture.storyReleaseMapper).insert(any(StoryReleaseEntity.class));

        var view = fixture.service.rollback(1L, 7L,
                new RollbackReleaseRequest(9L, 3, "rollback-removes-later", true));

        assertThat(view.chapters()).hasSize(1);
        assertThat(view.releaseHash()).isEqualTo(fixture.sha256(
                "2:5:" + targetRevision.getContentHash() + "\n"));
        assertThat(laterChapter.getCurrentProseRevisionId()).isNull();
        assertThat(laterChapter.getContent()).isNull();
    }

    @Test
    void compareKeepsBothHistoricalRevisionBodies() {
        Fixture fixture = new Fixture();
        when(fixture.proseRevisionMapper.selectById(5L)).thenReturn(fixture.revision(5L, "published", "旧正文"));
        when(fixture.proseRevisionMapper.selectById(6L)).thenReturn(fixture.revision(6L, "confirmable", "新正文"));

        var diff = fixture.service.compareRevisions(1L, 2L, 5L, 6L);

        assertThat(diff.changed()).isTrue();
        assertThat(diff.baseContent()).isEqualTo("旧正文");
        assertThat(diff.targetContent()).isEqualTo("新正文");
    }

    private static final class Fixture {
        private final WorkMapper workMapper = mock(WorkMapper.class);
        private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
        private final ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        private final BoundedChapterRevisionMapper boundedRevisionMapper = mock(BoundedChapterRevisionMapper.class);
        private final ChapterGenerationEvaluationReportMapper evaluationReportMapper =
                mock(ChapterGenerationEvaluationReportMapper.class);
        private final ChapterProseRevisionMapper proseRevisionMapper = mock(ChapterProseRevisionMapper.class);
        private final StoryReleaseMapper storyReleaseMapper = mock(StoryReleaseMapper.class);
        private final StoryReleaseChapterMapper releaseChapterMapper = mock(StoryReleaseChapterMapper.class);
        private final WorkRevisionWorkspaceMapper workspaceMapper = mock(WorkRevisionWorkspaceMapper.class);
        private final WorkRevisionWorkspaceChapterMapper workspaceChapterMapper =
                mock(WorkRevisionWorkspaceChapterMapper.class);
        private final GenerationEvaluationService evaluationService = mock(GenerationEvaluationService.class);
        private final WorkEntity work = work();
        private final ChapterEntity chapter = chapter();
        private final StoryReleaseServiceImpl service = new StoryReleaseServiceImpl(
                workMapper, chapterMapper, generationMapper, boundedRevisionMapper, evaluationReportMapper,
                proseRevisionMapper, storyReleaseMapper, releaseChapterMapper, workspaceMapper,
                workspaceChapterMapper, evaluationService, new ObjectMapper());

        private Fixture() {
            when(workMapper.selectById(1L)).thenReturn(work);
            when(chapterMapper.selectById(2L)).thenReturn(chapter);
            when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter);
        }

        private ChapterProseRevisionEntity revision(Long id, String status, String content) {
            ChapterProseRevisionEntity item = new ChapterProseRevisionEntity();
            item.setId(id);
            item.setWorkId(1L);
            item.setChapterId(2L);
            item.setRevisionNo(id.intValue());
            item.setRevisionStatus(status);
            item.setRevisionOrigin("manual");
            item.setContent(content);
            item.setContentHash(sha256(content));
            item.setDeleted(0);
            item.setVersion(0);
            return item;
        }

        private ChapterGenerationEvaluationReportEntity report(Long id, Long generationId, String contentHash) {
            ChapterGenerationEvaluationReportEntity item = new ChapterGenerationEvaluationReportEntity();
            item.setId(id);
            item.setWorkId(1L);
            item.setChapterId(2L);
            item.setGenerationId(generationId);
            item.setReportStatus("ready");
            item.setConclusion("pass");
            item.setContentHash(contentHash);
            item.setDeleted(0);
            item.setVersion(0);
            return item;
        }

        private ChapterGenerationEntity generation(Long id, String content) {
            ChapterGenerationEntity item = new ChapterGenerationEntity();
            item.setId(id);
            item.setWorkId(1L);
            item.setChapterId(2L);
            item.setGeneratedContent(content);
            item.setDeleted(0);
            item.setVersion(0);
            return item;
        }

        private BoundedChapterRevisionEntity boundedRevision(Long id, Long resultGenerationId) {
            BoundedChapterRevisionEntity item = new BoundedChapterRevisionEntity();
            item.setId(id);
            item.setWorkId(1L);
            item.setChapterId(2L);
            item.setResultGenerationId(resultGenerationId);
            item.setResultContentHash(sha256("#106 新候选"));
            item.setDeleted(0);
            item.setVersion(0);
            return item;
        }

        private WorkRevisionWorkspaceEntity workspace(String status, int version) {
            WorkRevisionWorkspaceEntity item = new WorkRevisionWorkspaceEntity();
            item.setId(10L);
            item.setWorkId(1L);
            item.setWorkspaceStatus(status);
            item.setBlockingItemsJson("[]");
            item.setDeleted(0);
            item.setVersion(version);
            return item;
        }

        private StoryReleaseEntity release(Long id, Long parentReleaseId, Long rollbackOfReleaseId) {
            StoryReleaseEntity item = new StoryReleaseEntity();
            item.setId(id);
            item.setWorkId(1L);
            item.setParentReleaseId(parentReleaseId);
            item.setRollbackOfReleaseId(rollbackOfReleaseId);
            item.setReleaseNo(id.intValue());
            item.setReleaseStatus("current");
            item.setReleaseHash("hash-" + id);
            item.setDeleted(0);
            item.setVersion(1);
            return item;
        }

        private StoryReleaseChapterEntity mapping(
                Long releaseId,
                Long chapterId,
                Long revisionId,
                int chapterNo,
                String contentHash) {
            StoryReleaseChapterEntity item = new StoryReleaseChapterEntity();
            item.setReleaseId(releaseId);
            item.setWorkId(1L);
            item.setChapterId(chapterId);
            item.setProseRevisionId(revisionId);
            item.setChapterNo(chapterNo);
            item.setContentHash(contentHash);
            item.setDeleted(0);
            item.setVersion(0);
            return item;
        }

        private ChapterEntity chapter(
                Long id,
                int chapterNo,
                String content,
                int version,
                Long revisionId) {
            ChapterEntity item = new ChapterEntity();
            item.setId(id);
            item.setWorkId(1L);
            item.setChapterNo(chapterNo);
            item.setContent(content);
            item.setCurrentProseRevisionId(revisionId);
            item.setDeleted(0);
            item.setVersion(version);
            return item;
        }

        private static WorkEntity work() {
            WorkEntity item = new WorkEntity();
            item.setId(1L);
            item.setDeleted(0);
            item.setVersion(3);
            return item;
        }

        private static ChapterEntity chapter() {
            ChapterEntity item = new ChapterEntity();
            item.setId(2L);
            item.setWorkId(1L);
            item.setChapterNo(1);
            item.setContent("已发布正文");
            item.setDeleted(0);
            item.setVersion(4);
            return item;
        }

        private static String sha256(String value) {
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
