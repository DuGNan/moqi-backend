package com.dugnan.moqi.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.impact.ProseImpactModels.FactChange;
import com.dugnan.moqi.impact.ProseImpactModels.ImpactAnalysis;
import com.dugnan.moqi.impact.entity.ProseRevisionImpactReportEntity;
import com.dugnan.moqi.impact.mapper.ProseRevisionFactChangeMapper;
import com.dugnan.moqi.impact.mapper.ProseRevisionImpactReportMapper;
import com.dugnan.moqi.impact.mapper.ProseRevisionImpactedAssetMapper;
import com.dugnan.moqi.impact.mapper.StoryReleaseKnowledgeSourceMapper;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeCandidateMapper;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeExtractionBatchMapper;
import com.dugnan.moqi.knowledge.entity.StoryKnowledgeCandidateEntity;
import com.dugnan.moqi.knowledge.entity.StoryKnowledgeExtractionBatchEntity;
import com.dugnan.moqi.release.entity.WorkRevisionWorkspaceChapterEntity;
import com.dugnan.moqi.release.entity.WorkRevisionWorkspaceEntity;
import com.dugnan.moqi.release.mapper.ChapterProseRevisionMapper;
import com.dugnan.moqi.release.mapper.StoryReleaseChapterMapper;
import com.dugnan.moqi.release.mapper.WorkRevisionWorkspaceChapterMapper;
import com.dugnan.moqi.release.mapper.WorkRevisionWorkspaceMapper;
import com.dugnan.moqi.release.entity.ChapterProseRevisionEntity;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.sourcechain.ChapterAssetSourceChainService;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

class ProseImpactServiceImplTest {
    @ParameterizedTest
    @ValueSource(strings = {"local", "adjacent", "cross_chapter", "work", "unknown"})
    void validatesSupportedFactImpactScopes(String scope) {
        Fixture fixture = new Fixture();
        ImpactAnalysis result = fixture.service.validate(new ImpactAnalysis(scope, "摘要",
                List.of(fixture.change(scope, "objective", new BigDecimal("0.90")))), "林舟抵达北城");
        assertThat(result.impactScope()).isEqualTo(scope);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "language_only"})
    void noFactAndLanguageOnlyReportsContainNoFactChanges(String scope) {
        Fixture fixture = new Fixture();
        assertThat(fixture.service.validate(new ImpactAnalysis(scope, "只调整表达", List.of()), "林舟抵达北城")
                .changes()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"character_claim", "rumor", "speculation", "unexplained", "author_backstage"})
    void preservesNonAuthoritativeEpistemicStatus(String epistemicStatus) {
        Fixture fixture = new Fixture();
        ImpactAnalysis result = fixture.service.validate(new ImpactAnalysis("local", "非权威叙述",
                List.of(fixture.change("local", epistemicStatus, new BigDecimal("0.88")))), "林舟抵达北城");
        assertThat(result.changes().get(0).epistemicStatus()).isEqualTo(epistemicStatus);
    }

    @Test
    void rejectsEvidenceThatDoesNotExactlyMatchTargetRevision() {
        Fixture fixture = new Fixture();
        FactChange invalid = new FactChange("fact-1", "event", "objective", "modified", "local",
                "抵达南城", 2, 6, new BigDecimal("0.90"), true, "地点变化");
        assertThatThrownBy(() -> fixture.service.validate(
                new ImpactAnalysis("local", "摘要", List.of(invalid)), "林舟抵达北城"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("证据");
    }

    @Test
    void normalizesOffsetsWhenEvidenceTextHasOneExactMatch() {
        Fixture fixture = new Fixture();
        FactChange wrongOffsets = new FactChange("fact-1", "event", "objective", "modified", "local",
                "抵达北城", 0, 4, new BigDecimal("0.90"), true, "地点变化");

        ImpactAnalysis result = fixture.service.validate(
                new ImpactAnalysis("local", "摘要", List.of(wrongOffsets)), "林舟抵达北城");

        assertThat(result.changes().get(0).evidenceStartOffset()).isEqualTo(2);
        assertThat(result.changes().get(0).evidenceEndOffset()).isEqualTo(6);
    }

    @Test
    void normalizesOffsetsUsingJavaUtf16Indexes() {
        Fixture fixture = new Fixture();
        String target = "雨夜🌧️林舟抵达北城";
        FactChange codePointOffsets = new FactChange("fact-1", "event", "objective", "modified", "local",
                "林舟抵达北城", 4, 10, new BigDecimal("0.90"), true, "地点变化");

        ImpactAnalysis result = fixture.service.validate(
                new ImpactAnalysis("local", "摘要", List.of(codePointOffsets)), target);

        assertThat(result.changes().get(0).evidenceStartOffset()).isEqualTo(target.indexOf("林舟抵达北城"));
        assertThat(result.changes().get(0).evidenceEndOffset())
                .isEqualTo(target.indexOf("林舟抵达北城") + "林舟抵达北城".length());
    }

    @Test
    void fillsMissingOffsetsOnlyForUniqueExactEvidence() {
        Fixture fixture = new Fixture();
        FactChange missingOffsets = new FactChange("fact-1", "event", "objective", "modified", "local",
                "抵达北城", null, null, new BigDecimal("0.90"), true, "地点变化");

        ImpactAnalysis result = fixture.service.validate(
                new ImpactAnalysis("local", "摘要", List.of(missingOffsets)), "林舟抵达北城");

        assertThat(result.changes().get(0).evidenceStartOffset()).isEqualTo(2);
        assertThat(result.changes().get(0).evidenceEndOffset()).isEqualTo(6);
    }

    @Test
    void rejectsAmbiguousEvidenceWhenOffsetsDoNotIdentifyOneOccurrence() {
        Fixture fixture = new Fixture();
        FactChange ambiguous = new FactChange("fact-1", "event", "objective", "modified", "local",
                "北城", 1, 3, new BigDecimal("0.90"), true, "地点变化");

        assertThatThrownBy(() -> fixture.service.validate(
                new ImpactAnalysis("local", "摘要", List.of(ambiguous)), "北城与北城"))
                .isInstanceOf(ProseImpactContractException.class)
                .satisfies(exception -> {
                    ProseImpactContractException contractException = (ProseImpactContractException) exception;
                    assertThat(contractException.category()).isEqualTo("ambiguous_reference");
                    assertThat(contractException.path()).isEqualTo("changes[0].evidenceText");
                });
    }

    @Test
    void preservesExactOffsetsWhenRepeatedTextIsAlreadyDisambiguated() {
        Fixture fixture = new Fixture();
        FactChange disambiguated = new FactChange("fact-1", "event", "objective", "modified", "local",
                "北城", 3, 5, new BigDecimal("0.90"), true, "地点变化");

        ImpactAnalysis result = fixture.service.validate(
                new ImpactAnalysis("local", "摘要", List.of(disambiguated)), "北城与北城");

        assertThat(result.changes().get(0).evidenceStartOffset()).isEqualTo(3);
        assertThat(result.changes().get(0).evidenceEndOffset()).isEqualTo(5);
    }

    @Test
    void rejectsEvidenceThatHasNoExactOccurrence() {
        Fixture fixture = new Fixture();
        FactChange missing = new FactChange("fact-1", "event", "objective", "modified", "local",
                "抵达南城", 0, 4, new BigDecimal("0.90"), true, "地点变化");

        assertThatThrownBy(() -> fixture.service.validate(
                new ImpactAnalysis("local", "摘要", List.of(missing)), "林舟抵达北城"))
                .isInstanceOf(ProseImpactContractException.class)
                .satisfies(exception -> {
                    ProseImpactContractException contractException = (ProseImpactContractException) exception;
                    assertThat(contractException.category()).isEqualTo("invalid_reference");
                    assertThat(contractException.path()).isEqualTo("changes[0].evidenceText");
                });
    }

    @Test
    void mapsContractCategoryToStableReportErrorCode() {
        Fixture fixture = new Fixture();

        assertThat(fixture.service.errorCode(
                new ProseImpactContractException("ambiguous_reference", "changes[0].evidenceText")))
                .isEqualTo("impact_output_ambiguous_reference");
    }

    @Test
    void validatesLocalAdjacentAndExplicitCrossChapterReferencesWithinWork() {
        Fixture fixture = new Fixture();
        when(fixture.chapterMapper.selectList(any())).thenReturn(fixture.chapters());

        FactChange local = fixture.change("local", "objective", new BigDecimal("0.90"), List.of(2L));
        assertThat(fixture.service.validate(new ImpactAnalysis("local", "局部", List.of(local)),
                "林舟抵达北城", 1L, 2L).changes()).hasSize(1);

        FactChange adjacent = fixture.change("adjacent", "objective", new BigDecimal("0.90"), List.of(2L, 3L));
        assertThat(fixture.service.validate(new ImpactAnalysis("adjacent", "相邻", List.of(adjacent)),
                "林舟抵达北城", 1L, 2L).changes()).hasSize(1);

        FactChange cross = fixture.change("cross_chapter", "objective", new BigDecimal("0.90"), List.of(2L, 4L));
        assertThat(fixture.service.validate(new ImpactAnalysis("cross_chapter", "跨章", List.of(cross)),
                "林舟抵达北城", 1L, 2L).changes().get(0).affectedChapterIds()).containsExactly(2L, 4L);
    }

    @Test
    void rejectsAdjacentOutsideNeighborAndChapterFromAnotherWork() {
        Fixture fixture = new Fixture();
        when(fixture.chapterMapper.selectList(any())).thenReturn(fixture.chapters());
        FactChange outsideAdjacent = fixture.change(
                "adjacent", "objective", new BigDecimal("0.90"), List.of(2L, 4L));
        assertThatThrownBy(() -> fixture.service.validate(
                new ImpactAnalysis("adjacent", "越界", List.of(outsideAdjacent)),
                "林舟抵达北城", 1L, 2L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("相邻章节");

        FactChange anotherWork = fixture.change(
                "cross_chapter", "objective", new BigDecimal("0.90"), List.of(2L, 99L));
        assertThatThrownBy(() -> fixture.service.validate(
                new ImpactAnalysis("cross_chapter", "跨作品", List.of(anotherWork)),
                "林舟抵达北城", 1L, 2L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前作品");
    }

    @Test
    void workUnknownAndFactionRuleAlwaysRequireHuman() {
        Fixture fixture = new Fixture();
        assertThat(fixture.service.requiresHuman(new ImpactAnalysis("work", "全局", List.of()))).isTrue();
        assertThat(fixture.service.requiresHuman(new ImpactAnalysis("unknown", "未知", List.of()))).isTrue();
        FactChange rule = new FactChange("rule", "faction_rule", "objective", "modified", "local",
                "抵达北城", 2, 6, new BigDecimal("0.95"), true, "规则变化", List.of(2L));
        assertThat(fixture.service.requiresHuman(new ImpactAnalysis("local", "规则", List.of(rule)))).isTrue();
    }

    @Test
    void workspaceGateBlocksMissingFailedAndLowConfidenceReports() {
        Fixture fixture = new Fixture();
        WorkRevisionWorkspaceEntity workspace = new WorkRevisionWorkspaceEntity();
        workspace.setId(10L); workspace.setWorkId(1L); workspace.setDeleted(0);
        WorkRevisionWorkspaceChapterEntity entry = new WorkRevisionWorkspaceChapterEntity();
        entry.setWorkspaceId(10L); entry.setProseRevisionId(6L); entry.setDeleted(0);
        when(fixture.workspaceMapper.selectById(10L)).thenReturn(workspace);
        when(fixture.workspaceChapterMapper.selectList(any())).thenReturn(List.of(entry));
        ProseRevisionImpactReportEntity failed = fixture.report("failed", 1);
        when(fixture.reportMapper.selectOne(any())).thenReturn(failed);
        assertThat(fixture.service.workspaceBlockingItems(1L, 10L))
                .containsExactly("impact_report_not_ready:20");
        failed.setReportStatus("ready");
        assertThat(fixture.service.workspaceBlockingItems(1L, 10L))
                .containsExactly("impact_report_blocking:20");
    }

    @Test
    void workspaceGateRequiresReadyKnowledgeBatchAndTerminalUserHandledCandidates() {
        Fixture fixture = new Fixture();
        fixture.workspaceWithRevision(10L, 6L);
        ProseRevisionImpactReportEntity ready = fixture.report("ready", 0);
        ready.setImpactScope("local");
        when(fixture.reportMapper.selectOne(any())).thenReturn(ready);

        assertThat(fixture.service.workspaceBlockingItems(1L, 10L))
                .containsExactly("knowledge_batch_missing:6");
        assertThat(fixture.service.workspaceSummary(1L, 10L).blockingItems())
                .singleElement().extracting(ProseImpactModels.ImpactBlockingItem::code)
                .isEqualTo("knowledge_batch_missing");

        StoryKnowledgeExtractionBatchEntity batch = fixture.batch(70L, "ready");
        when(fixture.batchMapper.selectList(any())).thenReturn(List.of(batch));
        StoryKnowledgeCandidateEntity pending = fixture.candidate(80L, "pending", 70L, 900L);
        when(fixture.candidateMapper.selectList(any())).thenReturn(List.of(pending));
        assertThat(fixture.service.workspaceBlockingItems(1L, 10L))
                .containsExactly("knowledge_candidate_not_handled:80:pending");

        pending.setCandidateStatus("ignored");
        assertThat(fixture.service.workspaceBlockingItems(1L, 10L)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "conflict", "duplicate", "queued", "running", "failed", "stale"})
    void workspaceGateBlocksEveryNonTerminalKnowledgeCandidateStatus(String status) {
        Fixture fixture = new Fixture();
        fixture.workspaceWithRevision(10L, 6L);
        ProseRevisionImpactReportEntity ready = fixture.report("ready", 0);
        ready.setImpactScope("local");
        when(fixture.reportMapper.selectOne(any())).thenReturn(ready);
        when(fixture.batchMapper.selectList(any())).thenReturn(List.of(fixture.batch(70L, "ready")));
        when(fixture.candidateMapper.selectList(any()))
                .thenReturn(List.of(fixture.candidate(80L, status, 70L, 900L)));

        assertThat(fixture.service.workspaceBlockingItems(1L, 10L))
                .containsExactly("knowledge_candidate_not_handled:80:" + status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"queued", "running", "failed", "stale"})
    void workspaceGateBlocksLatestKnowledgeBatchUntilReady(String status) {
        Fixture fixture = new Fixture();
        fixture.workspaceWithRevision(10L, 6L);
        ProseRevisionImpactReportEntity ready = fixture.report("ready", 0);
        ready.setImpactScope("local");
        when(fixture.reportMapper.selectOne(any())).thenReturn(ready);
        when(fixture.batchMapper.selectList(any())).thenReturn(List.of(fixture.batch(70L, status)));

        assertThat(fixture.service.workspaceBlockingItems(1L, 10L))
                .containsExactly("knowledge_batch_not_ready:70:" + status);
    }

    @Test
    void sourceGraphChangeMakesReadyReportStaleAndBlocksWorkspace() {
        Fixture fixture = new Fixture();
        fixture.workspaceWithRevision(10L, 6L);
        ProseRevisionImpactReportEntity ready = fixture.report("ready", 0);
        when(fixture.reportMapper.selectOne(any())).thenReturn(ready);
        when(fixture.reportMapper.selectById(20L)).thenReturn(ready);
        var snapshot = new com.dugnan.moqi.sourcechain.entity.ChapterAssetSourceSnapshotEntity();
        snapshot.setId(91L); snapshot.setWorkId(1L); snapshot.setChapterId(3L);
        snapshot.setAssetType("brief"); snapshot.setAssetId(92L); snapshot.setAssetVersion(2);
        snapshot.setSourceContentHash("new-source"); snapshot.setDeleted(0); snapshot.setVersion(0);
        when(fixture.sourceSnapshotMapper.selectList(any())).thenReturn(List.of(snapshot));

        assertThat(fixture.service.detail(1L, 2L, 6L, 20L).reportStatus()).isEqualTo("stale");
        assertThat(fixture.service.workspaceBlockingItems(1L, 10L))
                .containsExactly("impact_report_stale:20");
    }

    @Test
    void analyzerVersionOrRevisionHashChangeMakesOldConclusionStale() {
        Fixture fixture = new Fixture();
        ProseRevisionImpactReportEntity ready = fixture.report("ready", 0);
        when(fixture.reportMapper.selectById(20L)).thenReturn(ready);

        ready.setAnalyzerVersion("old-analyzer");
        assertThat(fixture.service.detail(1L, 2L, 6L, 20L).reportStatus()).isEqualTo("stale");

        ready.setAnalyzerVersion(ProseImpactServiceImpl.ANALYZER_VERSION);
        ChapterProseRevisionEntity changed = fixture.revision(6L, "正文已变化");
        changed.setContentHash("changed-hash");
        when(fixture.revisionMapper.selectById(6L)).thenReturn(changed);
        assertThat(fixture.service.detail(1L, 2L, 6L, 20L).reportStatus()).isEqualTo("stale");

        assertThatThrownBy(() -> fixture.service.detail(1L, 3L, 6L, 20L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.service.detail(1L, 2L, 7L, 20L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void workspaceBaselineReleaseMappingOverridesArbitraryRevisionParent() {
        Fixture fixture = new Fixture();
        ChapterProseRevisionEntity target = fixture.revision(6L, "林舟抵达北城");
        target.setParentRevisionId(5L);
        ChapterProseRevisionEntity released = fixture.revision(4L, "林舟抵达旧城");
        when(fixture.revisionMapper.selectById(6L)).thenReturn(target);
        when(fixture.revisionMapper.selectById(4L)).thenReturn(released);
        WorkRevisionWorkspaceEntity workspace = fixture.workspaceWithRevision(10L, 6L);
        workspace.setBaselineReleaseId(30L);
        var releaseMapping = new com.dugnan.moqi.release.entity.StoryReleaseChapterEntity();
        releaseMapping.setReleaseId(30L); releaseMapping.setWorkId(1L); releaseMapping.setChapterId(2L);
        releaseMapping.setProseRevisionId(4L); releaseMapping.setDeleted(0);
        when(fixture.releaseChapterMapper.selectOne(any())).thenReturn(releaseMapping);
        when(fixture.reportMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> { ((ProseRevisionImpactReportEntity) invocation.getArgument(0)).setId(20L); return 1; })
                .when(fixture.reportMapper).insert(any(ProseRevisionImpactReportEntity.class));
        when(fixture.reportMapper.update(any(), any())).thenReturn(1);
        when(fixture.agentRuntime.start(any())).thenReturn(fixture.run());

        var created = fixture.service.create(1L, 2L, 6L,
                new ProseImpactModels.CreateReportRequest(10L, null, "released-baseline"));

        assertThat(created.report().baselineReleaseId()).isEqualTo(30L);
        assertThat(created.report().baselineRevisionId()).isEqualTo(4L);
    }

    @Test
    void rejectsRequestedBaselineThatDiffersFromReleasedChapterMapping() {
        Fixture fixture = new Fixture();
        when(fixture.revisionMapper.selectById(6L)).thenReturn(fixture.revision(6L, "林舟抵达北城"));
        WorkRevisionWorkspaceEntity workspace = fixture.workspaceWithRevision(10L, 6L);
        workspace.setBaselineReleaseId(30L);
        var releaseMapping = new com.dugnan.moqi.release.entity.StoryReleaseChapterEntity();
        releaseMapping.setReleaseId(30L); releaseMapping.setWorkId(1L); releaseMapping.setChapterId(2L);
        releaseMapping.setProseRevisionId(4L); releaseMapping.setDeleted(0);
        when(fixture.releaseChapterMapper.selectOne(any())).thenReturn(releaseMapping);

        assertThatThrownBy(() -> fixture.service.create(1L, 2L, 6L,
                new ProseImpactModels.CreateReportRequest(10L, 5L, "wrong-baseline")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("baseline");
    }

    @Test
    void baselineReleaseMayOmitNewChapterButCannotBorrowArbitraryRevision() {
        Fixture fixture = new Fixture();
        when(fixture.revisionMapper.selectById(6L)).thenReturn(fixture.revision(6L, "新增章节正文"));
        WorkRevisionWorkspaceEntity workspace = fixture.workspaceWithRevision(10L, 6L);
        workspace.setBaselineReleaseId(30L);
        when(fixture.releaseChapterMapper.selectOne(any())).thenReturn(null);
        when(fixture.reportMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> { ((ProseRevisionImpactReportEntity) invocation.getArgument(0)).setId(20L); return 1; })
                .when(fixture.reportMapper).insert(any(ProseRevisionImpactReportEntity.class));
        when(fixture.reportMapper.update(any(), any())).thenReturn(1);
        when(fixture.agentRuntime.start(any())).thenReturn(fixture.run());

        var created = fixture.service.create(1L, 2L, 6L,
                new ProseImpactModels.CreateReportRequest(10L, null, "new-chapter"));
        assertThat(created.report().baselineReleaseId()).isEqualTo(30L);
        assertThat(created.report().baselineRevisionId()).isNull();

        assertThatThrownBy(() -> fixture.service.create(1L, 2L, 6L,
                new ProseImpactModels.CreateReportRequest(10L, 5L, "borrowed-baseline")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("新增章节");
    }

    @Test
    void noWorkspaceUsesCurrentStoryReleaseBaselineAndFirstReleaseRejectsArbitraryBaseline() {
        Fixture fixture = new Fixture();
        ChapterProseRevisionEntity target = fixture.revision(6L, "林舟抵达北城");
        ChapterProseRevisionEntity released = fixture.revision(4L, "林舟抵达旧城");
        when(fixture.revisionMapper.selectById(6L)).thenReturn(target);
        when(fixture.revisionMapper.selectById(4L)).thenReturn(released);
        var currentWork = new com.dugnan.moqi.work.entity.WorkEntity();
        currentWork.setId(1L); currentWork.setCurrentStoryReleaseId(30L); currentWork.setDeleted(0);
        when(fixture.workMapper.selectById(1L)).thenReturn(currentWork);
        var releaseMapping = new com.dugnan.moqi.release.entity.StoryReleaseChapterEntity();
        releaseMapping.setReleaseId(30L); releaseMapping.setWorkId(1L); releaseMapping.setChapterId(2L);
        releaseMapping.setProseRevisionId(4L); releaseMapping.setDeleted(0);
        when(fixture.releaseChapterMapper.selectOne(any())).thenReturn(releaseMapping);
        when(fixture.reportMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> { ((ProseRevisionImpactReportEntity) invocation.getArgument(0)).setId(20L); return 1; })
                .when(fixture.reportMapper).insert(any(ProseRevisionImpactReportEntity.class));
        when(fixture.reportMapper.update(any(), any())).thenReturn(1);
        when(fixture.agentRuntime.start(any())).thenReturn(fixture.run());

        var created = fixture.service.create(1L, 2L, 6L,
                new ProseImpactModels.CreateReportRequest(null, null, "current-release"));
        assertThat(created.report().baselineReleaseId()).isEqualTo(30L);
        assertThat(created.report().baselineRevisionId()).isEqualTo(4L);

        currentWork.setCurrentStoryReleaseId(null);
        assertThatThrownBy(() -> fixture.service.create(1L, 2L, 6L,
                new ProseImpactModels.CreateReportRequest(null, 4L, "first-release-arbitrary")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("首次 Story Release");
    }

    @Test
    void releaseActivationPropagatesOnlyAfterReleaseHookRuns() {
        Fixture fixture = new Fixture();
        ProseRevisionImpactReportEntity ready = fixture.report("ready", 0);
        ready.setImpactScope("local");
        when(fixture.reportMapper.selectOne(any())).thenReturn(ready);
        var mapping = new com.dugnan.moqi.release.entity.StoryReleaseChapterEntity();
        mapping.setReleaseId(30L); mapping.setChapterId(2L); mapping.setProseRevisionId(6L); mapping.setDeleted(0);
        when(fixture.releaseChapterMapper.selectList(any())).thenReturn(List.of(mapping));
        var chapter = new com.dugnan.moqi.work.entity.ChapterEntity();
        chapter.setId(2L); chapter.setWorkId(1L); chapter.setChapterNo(1); chapter.setDeleted(0);
        when(fixture.chapterMapper.selectList(any())).thenReturn(List.of(chapter));
        when(fixture.batchMapper.selectList(any())).thenReturn(List.of());
        var changed = new com.dugnan.moqi.impact.entity.ProseRevisionFactChangeEntity();
        changed.setReportId(20L); changed.setAffectedChapterIdsJson("[2]"); changed.setDeleted(0);
        when(fixture.changeMapper.selectList(any())).thenReturn(List.of(changed));

        fixture.service.activateRelease(1L, 30L, 29L, null);

        verify(fixture.sourceChainService).markNeedsReview(2L, "prose-release:30:report:20",
                List.of("published_prose_fact_changed", "impact_scope_local"));
    }

    @Test
    void releaseKnowledgeMappingsDeduplicateSameConfirmedTargetDeterministically() {
        Fixture fixture = new Fixture();
        var mapping = new com.dugnan.moqi.release.entity.StoryReleaseChapterEntity();
        mapping.setReleaseId(30L); mapping.setChapterId(2L); mapping.setProseRevisionId(6L); mapping.setDeleted(0);
        when(fixture.releaseChapterMapper.selectList(any())).thenReturn(List.of(mapping));
        when(fixture.batchMapper.selectList(any())).thenReturn(List.of(fixture.batch(70L, "ready")));
        StoryKnowledgeCandidateEntity first = fixture.candidate(80L, "confirmed", 70L, 900L);
        StoryKnowledgeCandidateEntity second = fixture.candidate(81L, "confirmed", 70L, 900L);
        when(fixture.candidateMapper.selectList(any())).thenReturn(List.of(second, first));
        when(fixture.reportMapper.selectOne(any())).thenReturn(null);

        fixture.service.activateRelease(1L, 30L, 29L, null);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.dugnan.moqi.impact.entity.StoryReleaseKnowledgeSourceEntity.class);
        verify(fixture.knowledgeSourceMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getSourceCandidateId()).isEqualTo(80L);
    }

    @Test
    void rollbackCopiesHistoricalKnowledgeMappingToNewRelease() {
        Fixture fixture = new Fixture();
        var historical = new com.dugnan.moqi.impact.entity.StoryReleaseKnowledgeSourceEntity();
        historical.setWorkId(1L); historical.setReleaseId(7L); historical.setChapterId(2L);
        historical.setProseRevisionId(6L); historical.setKnowledgeType("setting"); historical.setKnowledgeId(900L);
        historical.setSourceCandidateId(80L); historical.setSourceStatus("superseded"); historical.setDeleted(0);
        when(fixture.knowledgeSourceMapper.selectList(any())).thenReturn(List.of(historical));
        when(fixture.releaseChapterMapper.selectList(any())).thenReturn(List.of());

        fixture.service.activateRelease(1L, 30L, 29L, 7L);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.dugnan.moqi.impact.entity.StoryReleaseKnowledgeSourceEntity.class);
        verify(fixture.knowledgeSourceMapper).insert(captor.capture());
        assertThat(captor.getValue().getReleaseId()).isEqualTo(30L);
        assertThat(captor.getValue().getKnowledgeId()).isEqualTo(900L);
        assertThat(captor.getValue().getSourceCandidateId()).isEqualTo(80L);
    }

    @Test
    void knowledgeInsertFailureDoesNotDeactivatePreviousReleaseMappings() {
        Fixture fixture = new Fixture();
        var mapping = new com.dugnan.moqi.release.entity.StoryReleaseChapterEntity();
        mapping.setReleaseId(30L); mapping.setChapterId(2L); mapping.setProseRevisionId(6L); mapping.setDeleted(0);
        when(fixture.releaseChapterMapper.selectList(any())).thenReturn(List.of(mapping));
        when(fixture.batchMapper.selectList(any())).thenReturn(List.of(fixture.batch(70L, "ready")));
        when(fixture.candidateMapper.selectList(any()))
                .thenReturn(List.of(fixture.candidate(80L, "confirmed", 70L, 900L)));
        when(fixture.knowledgeSourceMapper.insert(
                any(com.dugnan.moqi.impact.entity.StoryReleaseKnowledgeSourceEntity.class)))
                .thenThrow(new IllegalStateException("mapping failed"));

        assertThatThrownBy(() -> fixture.service.activateRelease(1L, 30L, 29L, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mapping failed");
        verify(fixture.knowledgeSourceMapper, org.mockito.Mockito.never()).update(any(), any());
    }

    @Test
    void createsIdempotentReportWithoutChangingAuthoritativeAssets() {
        Fixture fixture = new Fixture();
        ChapterProseRevisionEntity target = fixture.revision(6L, "林舟抵达北城");
        when(fixture.revisionMapper.selectById(6L)).thenReturn(target);
        when(fixture.reportMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> { ((ProseRevisionImpactReportEntity) invocation.getArgument(0)).setId(20L); return 1; })
                .when(fixture.reportMapper).insert(any(ProseRevisionImpactReportEntity.class));
        when(fixture.reportMapper.update(any(), any())).thenReturn(1);
        AgentRunView run = new AgentRunView(51L, ProseImpactServiceImpl.WORKFLOW_TYPE, "queued", 1L, 2L,
                null, "prepare", 0L, null, null, null, null, null);
        when(fixture.agentRuntime.start(any())).thenReturn(run);

        var created = fixture.service.create(1L, 2L, 6L,
                new ProseImpactModels.CreateReportRequest(null, null, "impact-1"));

        assertThat(created.report().reportStatus()).isEqualTo("queued");
        assertThat(created.run().runId()).isEqualTo(51L);
        verify(fixture.reportMapper).insert(any(ProseRevisionImpactReportEntity.class));
    }

    @Test
    void sameIdempotencyKeyWithDifferentRevisionFingerprintConflicts() {
        Fixture fixture = new Fixture();
        ChapterProseRevisionEntity target = fixture.revision(6L, "林舟抵达北城");
        when(fixture.revisionMapper.selectById(6L)).thenReturn(target);
        ProseRevisionImpactReportEntity existing = fixture.report("ready", 0);
        existing.setInputFingerprint("different");
        existing.setIdempotencyKey("impact-1");
        when(fixture.reportMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> fixture.service.create(1L, 2L, 6L,
                new ProseImpactModels.CreateReportRequest(null, null, "impact-1")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("幂等键");
    }

    @Test
    void concurrentIdempotencyInsertReturnsBusinessConflict() {
        Fixture fixture = new Fixture();
        when(fixture.revisionMapper.selectById(6L)).thenReturn(fixture.revision(6L, "林舟抵达北城"));
        when(fixture.reportMapper.selectOne(any())).thenReturn(null);
        when(fixture.reportMapper.insert(any(ProseRevisionImpactReportEntity.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("race"));

        assertThatThrownBy(() -> fixture.service.create(1L, 2L, 6L,
                new ProseImpactModels.CreateReportRequest(null, null, "concurrent-key")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("并发冲突");
    }

    @Test
    void retryUsesCasBeforeResumingFailedAnalysisStep() {
        Fixture fixture = new Fixture();
        ProseRevisionImpactReportEntity failed = fixture.report("failed", 1);
        failed.setAgentRunId(51L);
        when(fixture.reportMapper.selectById(20L)).thenReturn(failed);
        when(fixture.reportMapper.update(any(), any())).thenReturn(1);
        AgentRunView queued = new AgentRunView(51L, ProseImpactServiceImpl.WORKFLOW_TYPE, "queued", 1L, 2L,
                null, ProseImpactServiceImpl.ANALYZE_STEP, 1L, null, null, null, null, null);
        when(fixture.agentRuntime.retryStep(any())).thenReturn(queued);

        AgentRunView result = fixture.service.retry(
                1L, 2L, 6L, 20L, new ProseImpactModels.RetryReportRequest(2));

        assertThat(result).isEqualTo(queued);
        verify(fixture.agentRuntime).retryStep(new com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand(
                51L, ProseImpactServiceImpl.ANALYZE_STEP, 2));
    }

    @Test
    void retryConcurrentCasConflictDoesNotResumeAgent() {
        Fixture fixture = new Fixture();
        ProseRevisionImpactReportEntity failed = fixture.report("failed", 1);
        failed.setAgentRunId(51L);
        when(fixture.reportMapper.selectById(20L)).thenReturn(failed);
        when(fixture.reportMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.retry(
                1L, 2L, 6L, 20L, new ProseImpactModels.RetryReportRequest(2)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("并发冲突");
        verify(fixture.agentRuntime, org.mockito.Mockito.never()).retryStep(any());
    }

    @Test
    void retryRejectsReportFromAnotherChapterOrRevision() {
        Fixture fixture = new Fixture();
        ProseRevisionImpactReportEntity failed = fixture.report("failed", 1);
        failed.setAgentRunId(51L);
        when(fixture.reportMapper.selectById(20L)).thenReturn(failed);

        assertThatThrownBy(() -> fixture.service.retry(
                1L, 3L, 6L, 20L, new ProseImpactModels.RetryReportRequest(2)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.service.retry(
                1L, 2L, 7L, 20L, new ProseImpactModels.RetryReportRequest(2)))
                .isInstanceOf(BusinessException.class);
        verify(fixture.agentRuntime, org.mockito.Mockito.never()).retryStep(any());
    }

    private static final class Fixture {
        final ProseRevisionImpactReportMapper reportMapper = mock(ProseRevisionImpactReportMapper.class);
        final ProseRevisionFactChangeMapper changeMapper = mock(ProseRevisionFactChangeMapper.class);
        final ProseRevisionImpactedAssetMapper assetMapper = mock(ProseRevisionImpactedAssetMapper.class);
        final StoryReleaseKnowledgeSourceMapper knowledgeSourceMapper = mock(StoryReleaseKnowledgeSourceMapper.class);
        final ChapterProseRevisionMapper revisionMapper = mock(ChapterProseRevisionMapper.class);
        final WorkRevisionWorkspaceMapper workspaceMapper = mock(WorkRevisionWorkspaceMapper.class);
        final WorkRevisionWorkspaceChapterMapper workspaceChapterMapper = mock(WorkRevisionWorkspaceChapterMapper.class);
        final StoryReleaseChapterMapper releaseChapterMapper = mock(StoryReleaseChapterMapper.class);
        final StoryKnowledgeExtractionBatchMapper batchMapper = mock(StoryKnowledgeExtractionBatchMapper.class);
        final StoryKnowledgeCandidateMapper candidateMapper = mock(StoryKnowledgeCandidateMapper.class);
        final ChapterMapper chapterMapper = mock(ChapterMapper.class);
        final WorkMapper workMapper = mock(WorkMapper.class);
        final ChapterAssetSourceChainService sourceChainService = mock(ChapterAssetSourceChainService.class);
        final ChapterAssetSourceSnapshotMapper sourceSnapshotMapper = mock(ChapterAssetSourceSnapshotMapper.class);
        final AgentRuntime agentRuntime = mock(AgentRuntime.class);
        final ProseImpactServiceImpl service = new ProseImpactServiceImpl(reportMapper, changeMapper, assetMapper,
                knowledgeSourceMapper, revisionMapper, workspaceMapper, workspaceChapterMapper, releaseChapterMapper,
                batchMapper, candidateMapper, chapterMapper, workMapper, sourceChainService, sourceSnapshotMapper, agentRuntime,
                new ObjectMapper());

        Fixture() {
            when(sourceSnapshotMapper.selectList(any())).thenReturn(List.of());
            var work = new com.dugnan.moqi.work.entity.WorkEntity();
            work.setId(1L); work.setDeleted(0); work.setVersion(0);
            when(workMapper.selectById(1L)).thenReturn(work);
        }

        FactChange change(String scope, String epistemic, BigDecimal confidence) {
            return new FactChange("fact-1", "event", epistemic, "modified", scope, "抵达北城", 2, 6,
                    confidence, true, "事件地点或状态发生变化");
        }
        FactChange change(String scope, String epistemic, BigDecimal confidence, List<Long> chapterIds) {
            return new FactChange("fact-1", "event", epistemic, "modified", scope, "抵达北城", 2, 6,
                    confidence, true, "事件地点或状态发生变化", chapterIds);
        }

        List<com.dugnan.moqi.work.entity.ChapterEntity> chapters() {
            var previous = chapter(1L, 1L, 1);
            var current = chapter(2L, 1L, 2);
            var next = chapter(3L, 1L, 3);
            var remote = chapter(4L, 1L, 8);
            var anotherWork = chapter(99L, 9L, 1);
            return List.of(previous, current, next, remote, anotherWork);
        }

        com.dugnan.moqi.work.entity.ChapterEntity chapter(Long id, Long workId, int chapterNo) {
            var chapter = new com.dugnan.moqi.work.entity.ChapterEntity();
            chapter.setId(id); chapter.setWorkId(workId); chapter.setChapterNo(chapterNo);
            chapter.setDeleted(0); return chapter;
        }
        ProseRevisionImpactReportEntity report(String status, int blocking) {
            ProseRevisionImpactReportEntity report = new ProseRevisionImpactReportEntity();
            ChapterProseRevisionEntity target = revision(6L, "林舟抵达北城");
            when(revisionMapper.selectById(6L)).thenReturn(target);
            report.setId(20L); report.setWorkId(1L); report.setChapterId(2L); report.setTargetRevisionId(6L);
            report.setAnalyzerVersion(ProseImpactServiceImpl.ANALYZER_VERSION);
            report.setSourceGraphFingerprint(service.sourceGraphFingerprint(1L, 2L));
            report.setReportStatus(status); report.setBlocking(blocking); report.setDeleted(0); report.setVersion(1);
            report.setInputFingerprint(service.currentInputFingerprint(report));
            return report;
        }
        ChapterProseRevisionEntity revision(Long id, String content) {
            ChapterProseRevisionEntity revision = new ChapterProseRevisionEntity();
            revision.setId(id); revision.setWorkId(1L); revision.setChapterId(2L); revision.setContent(content);
            revision.setContentHash("hash-" + id); revision.setDeleted(0); revision.setVersion(0); return revision;
        }

        WorkRevisionWorkspaceEntity workspaceWithRevision(Long workspaceId, Long revisionId) {
            WorkRevisionWorkspaceEntity workspace = new WorkRevisionWorkspaceEntity();
            workspace.setId(workspaceId); workspace.setWorkId(1L); workspace.setDeleted(0);
            when(workspaceMapper.selectById(workspaceId)).thenReturn(workspace);
            WorkRevisionWorkspaceChapterEntity entry = new WorkRevisionWorkspaceChapterEntity();
            entry.setWorkspaceId(workspaceId); entry.setChapterId(2L); entry.setProseRevisionId(revisionId);
            entry.setDeleted(0);
            when(workspaceChapterMapper.selectList(any())).thenReturn(List.of(entry));
            when(workspaceChapterMapper.selectCount(any())).thenReturn(1L);
            return workspace;
        }

        StoryKnowledgeExtractionBatchEntity batch(Long id, String status) {
            StoryKnowledgeExtractionBatchEntity batch = new StoryKnowledgeExtractionBatchEntity();
            batch.setId(id); batch.setSourceProseRevisionId(6L); batch.setBatchStatus(status); batch.setDeleted(0);
            return batch;
        }

        StoryKnowledgeCandidateEntity candidate(Long id, String status, Long batchId, Long targetId) {
            StoryKnowledgeCandidateEntity candidate = new StoryKnowledgeCandidateEntity();
            candidate.setId(id); candidate.setBatchId(batchId); candidate.setCandidateStatus(status);
            candidate.setConfirmedTargetType("setting"); candidate.setConfirmedTargetId(targetId);
            candidate.setDeleted(0); return candidate;
        }

        AgentRunView run() {
            return new AgentRunView(51L, ProseImpactServiceImpl.WORKFLOW_TYPE, "queued", 1L, 2L,
                    null, "prepare", 0L, null, null, null, null, null);
        }
    }
}
