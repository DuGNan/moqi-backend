package com.dugnan.moqi.impact;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.impact.ProseImpactModels.CreateReportRequest;
import com.dugnan.moqi.impact.ProseImpactModels.CreateReportResult;
import com.dugnan.moqi.impact.ProseImpactModels.FactChange;
import com.dugnan.moqi.impact.ProseImpactModels.ImpactAnalysis;
import com.dugnan.moqi.impact.ProseImpactModels.ImpactedAssetView;
import com.dugnan.moqi.impact.ProseImpactModels.ImpactBlockingItem;
import com.dugnan.moqi.impact.ProseImpactModels.ReportView;
import com.dugnan.moqi.impact.ProseImpactModels.RetryReportRequest;
import com.dugnan.moqi.impact.ProseImpactModels.WorkspaceImpactSummary;
import com.dugnan.moqi.impact.entity.ProseRevisionFactChangeEntity;
import com.dugnan.moqi.impact.entity.ProseRevisionImpactReportEntity;
import com.dugnan.moqi.impact.entity.ProseRevisionImpactedAssetEntity;
import com.dugnan.moqi.impact.entity.StoryReleaseKnowledgeSourceEntity;
import com.dugnan.moqi.impact.mapper.ProseRevisionFactChangeMapper;
import com.dugnan.moqi.impact.mapper.ProseRevisionImpactReportMapper;
import com.dugnan.moqi.impact.mapper.ProseRevisionImpactedAssetMapper;
import com.dugnan.moqi.impact.mapper.StoryReleaseKnowledgeSourceMapper;
import com.dugnan.moqi.knowledge.entity.StoryKnowledgeCandidateEntity;
import com.dugnan.moqi.knowledge.entity.StoryKnowledgeExtractionBatchEntity;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeCandidateMapper;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeExtractionBatchMapper;
import com.dugnan.moqi.release.entity.ChapterProseRevisionEntity;
import com.dugnan.moqi.release.entity.StoryReleaseChapterEntity;
import com.dugnan.moqi.release.entity.WorkRevisionWorkspaceChapterEntity;
import com.dugnan.moqi.release.entity.WorkRevisionWorkspaceEntity;
import com.dugnan.moqi.release.mapper.ChapterProseRevisionMapper;
import com.dugnan.moqi.release.mapper.StoryReleaseChapterMapper;
import com.dugnan.moqi.release.mapper.WorkRevisionWorkspaceChapterMapper;
import com.dugnan.moqi.release.mapper.WorkRevisionWorkspaceMapper;
import com.dugnan.moqi.sourcechain.ChapterAssetSourceChainService;
import com.dugnan.moqi.sourcechain.entity.ChapterAssetSourceSnapshotEntity;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @description 实现正文影响报告的可恢复分析、确定性校验和发布期原子来源传播。
 */
@Service
public class ProseImpactServiceImpl implements ProseImpactService, ProseImpactReleaseHook {
    public static final String WORKFLOW_TYPE = "prose_revision_impact_v1";
    public static final String ANALYZE_STEP = "analyze_impact";
    public static final String ANALYZER_VERSION = "prose-impact-analyzer-v2";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_STALE = "stale";
    private static final String KNOWLEDGE_READY = "ready";
    private static final Set<String> KNOWLEDGE_TERMINAL_STATUSES = Set.of("confirmed", "ignored");
    private static final String SCOPE_NONE = "none";
    private static final String SCOPE_LANGUAGE_ONLY = "language_only";
    private static final String SCOPE_LOCAL = "local";
    private static final String SCOPE_ADJACENT = "adjacent";
    private static final String SCOPE_CROSS_CHAPTER = "cross_chapter";
    private static final String SCOPE_WORK = "work";
    private static final String SCOPE_UNKNOWN = "unknown";
    private static final String COMPLETE_CONFLICT_MESSAGE = "影响报告完成时发生并发冲突";
    private static final String RETRY_CONFLICT_MESSAGE = "影响报告重试时发生并发冲突";
    private static final String EMPTY_ANALYSIS_MESSAGE = "影响分析对象为空";
    private static final String INVALID_SCOPE_MESSAGE = "影响范围缺失或非法";
    private static final String EMPTY_SUMMARY_MESSAGE = "影响摘要缺失";
    private static final String CHAPTER_OUTSIDE_WORK_MESSAGE = "影响分析目标章节不属于当前作品";
    private static final int MAX_FACT_CHANGE_COUNT = 100;
    private static final Set<String> FACT_TYPES = Set.of("event", "character_state", "object_resource",
            "space_time_route", "causality", "faction_rule", "foreshadowing", "language_only");
    private static final Set<String> EPISTEMIC = Set.of("objective", "character_claim", "rumor", "speculation",
            "unexplained", "author_backstage");
    private static final Set<String> CHANGE_KINDS = Set.of("added", "removed", "modified", "reframed");
    private static final Set<String> SCOPES = Set.of(SCOPE_NONE, SCOPE_LANGUAGE_ONLY, "local", SCOPE_ADJACENT,
            SCOPE_CROSS_CHAPTER, SCOPE_WORK, SCOPE_UNKNOWN);

    private final ProseRevisionImpactReportMapper reportMapper;
    private final ProseRevisionFactChangeMapper changeMapper;
    private final ProseRevisionImpactedAssetMapper assetMapper;
    private final StoryReleaseKnowledgeSourceMapper knowledgeSourceMapper;
    private final ChapterProseRevisionMapper revisionMapper;
    private final WorkRevisionWorkspaceMapper workspaceMapper;
    private final WorkRevisionWorkspaceChapterMapper workspaceChapterMapper;
    private final StoryReleaseChapterMapper releaseChapterMapper;
    private final StoryKnowledgeExtractionBatchMapper batchMapper;
    private final StoryKnowledgeCandidateMapper candidateMapper;
    private final ChapterMapper chapterMapper;
    private final WorkMapper workMapper;
    private final ChapterAssetSourceChainService sourceChainService;
    private final ChapterAssetSourceSnapshotMapper sourceSnapshotMapper;
    private final AgentRuntime agentRuntime;
    private final ObjectMapper objectMapper;

    public ProseImpactServiceImpl(ProseRevisionImpactReportMapper reportMapper,
            ProseRevisionFactChangeMapper changeMapper, ProseRevisionImpactedAssetMapper assetMapper,
            StoryReleaseKnowledgeSourceMapper knowledgeSourceMapper, ChapterProseRevisionMapper revisionMapper,
            WorkRevisionWorkspaceMapper workspaceMapper, WorkRevisionWorkspaceChapterMapper workspaceChapterMapper,
            StoryReleaseChapterMapper releaseChapterMapper, StoryKnowledgeExtractionBatchMapper batchMapper,
            StoryKnowledgeCandidateMapper candidateMapper, ChapterMapper chapterMapper,
            WorkMapper workMapper,
            ChapterAssetSourceChainService sourceChainService, ChapterAssetSourceSnapshotMapper sourceSnapshotMapper,
            @Lazy AgentRuntime agentRuntime,
            ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.changeMapper = changeMapper;
        this.assetMapper = assetMapper;
        this.knowledgeSourceMapper = knowledgeSourceMapper;
        this.revisionMapper = revisionMapper;
        this.workspaceMapper = workspaceMapper;
        this.workspaceChapterMapper = workspaceChapterMapper;
        this.releaseChapterMapper = releaseChapterMapper;
        this.batchMapper = batchMapper;
        this.candidateMapper = candidateMapper;
        this.chapterMapper = chapterMapper;
        this.workMapper = workMapper;
        this.sourceChainService = sourceChainService;
        this.sourceSnapshotMapper = sourceSnapshotMapper;
        this.agentRuntime = agentRuntime;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public CreateReportResult create(Long workId, Long chapterId, Long targetRevisionId, CreateReportRequest request) {
        if (request == null || !StringUtils.hasText(request.idempotencyKey())) {
            throw badRequest("影响报告必须提供 idempotencyKey");
        }
        ChapterProseRevisionEntity target = requireRevision(workId, chapterId, targetRevisionId);
        WorkRevisionWorkspaceEntity workspace = request.workspaceId() == null ? null
                : requireWorkspace(workId, request.workspaceId());
        if (workspace != null && !workspaceContains(workspace.getId(), chapterId, targetRevisionId)) {
            throw conflict("影响报告目标 revision 未被工作区选中");
        }
        BaselineBinding baselineBinding = resolveBaseline(workId, chapterId, workspace, request.baselineRevisionId());
        ChapterProseRevisionEntity baseline = baselineBinding.revision();
        Long baselineId = baseline == null ? null : baseline.getId();
        String sourceGraphFingerprint = sourceGraphFingerprint(workId, chapterId);
        String fingerprint = fingerprint(baselineBinding.releaseId(), baseline, target,
                workspace == null ? null : workspace.getId(), ANALYZER_VERSION, sourceGraphFingerprint);
        ProseRevisionImpactReportEntity existing = byIdempotency(workId, request.idempotencyKey());
        if (existing != null) {
            if (!Objects.equals(existing.getInputFingerprint(), fingerprint)) {
                throw conflict("影响报告幂等键已绑定不同输入");
            }
            return new CreateReportResult(view(existing), existing.getAgentRunId() == null ? null
                    : agentRuntime.load(existing.getAgentRunId(), "local-user"));
        }
        ProseRevisionImpactReportEntity report = new ProseRevisionImpactReportEntity();
        report.setWorkId(workId);
        report.setChapterId(chapterId);
        report.setWorkspaceId(request.workspaceId());
        report.setBaselineRevisionId(baselineId);
        report.setTargetRevisionId(targetRevisionId);
        report.setBaselineReleaseId(baselineBinding.releaseId());
        report.setIdempotencyKey(request.idempotencyKey());
        report.setInputFingerprint(fingerprint);
        report.setSourceGraphFingerprint(sourceGraphFingerprint);
        report.setAnalyzerVersion(ANALYZER_VERSION);
        report.setReportStatus(STATUS_QUEUED);
        report.setBlocking(1);
        report.setDeleted(0);
        report.setVersion(0);
        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException exception) {
            throw conflict("影响报告创建时发生并发冲突");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reportId", report.getId());
        input.put("workId", workId);
        input.put("chapterId", chapterId);
        AgentRunView run = agentRuntime.start(new StartAgentRunCommand("local-user", workId, chapterId,
                WORKFLOW_TYPE, request.idempotencyKey(), target.getVersion().longValue(), input, null));
        int updated = reportMapper.update(null, new UpdateWrapper<ProseRevisionImpactReportEntity>()
                .eq("id", report.getId()).eq("version", 0).set("agent_run_id", run.runId())
                .set("version", 1).set("gmt_modified", LocalDateTime.now()));
        if (updated != 1) {
            throw conflict("影响报告关联 Agent Run 时发生并发冲突");
        }
        report.setAgentRunId(run.runId());
        report.setVersion(1);
        return new CreateReportResult(view(report), run);
    }

    @Override public ReportView detail(Long workId, Long chapterId, Long targetRevisionId, Long reportId) {
        return view(requireReportIdentity(workId, chapterId, targetRevisionId, reportId));
    }

    @Override public ReportView latest(Long workId, Long chapterId, Long targetRevisionId) {
        ProseRevisionImpactReportEntity report = reportMapper.selectOne(
                new LambdaQueryWrapper<ProseRevisionImpactReportEntity>()
                        .eq(ProseRevisionImpactReportEntity::getWorkId, workId)
                        .eq(ProseRevisionImpactReportEntity::getChapterId, chapterId)
                        .eq(ProseRevisionImpactReportEntity::getTargetRevisionId, targetRevisionId)
                        .eq(ProseRevisionImpactReportEntity::getDeleted, 0)
                        .orderByDesc(ProseRevisionImpactReportEntity::getId).last("LIMIT 1"));
        if (report == null) { throw notFound(); }
        return view(report);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView retry(Long workId, Long chapterId, Long targetRevisionId, Long reportId,
            RetryReportRequest request) {
        ProseRevisionImpactReportEntity report = requireReportIdentity(
                workId, chapterId, targetRevisionId, reportId);
        if (isStale(report)) {
            throw conflict("来源图、正文或分析器已变化，必须创建新影响报告");
        }
        if (!STATUS_FAILED.equals(report.getReportStatus()) || report.getAgentRunId() == null
                || request == null || request.expectedAttempt() == null) {
            throw conflict("当前影响报告不能重试");
        }
        int updated = reportMapper.update(null, new UpdateWrapper<ProseRevisionImpactReportEntity>()
                .eq("id", reportId).eq("version", report.getVersion()).eq("report_status", STATUS_FAILED)
                .set("report_status", STATUS_RUNNING).set("error_code", null).setSql("version = version + 1"));
        if (updated != 1) { throw conflict(RETRY_CONFLICT_MESSAGE); }
        return agentRuntime.retryStep(new RetryAgentStepCommand(report.getAgentRunId(), ANALYZE_STEP,
                request.expectedAttempt()));
    }

    public String analysisSource(Long reportId) {
        ProseRevisionImpactReportEntity report = requireFreshReport(reportId);
        ChapterProseRevisionEntity target = revisionMapper.selectById(report.getTargetRevisionId());
        ChapterProseRevisionEntity baseline = report.getBaselineRevisionId() == null ? null
                : revisionMapper.selectById(report.getBaselineRevisionId());
        return json(Map.of("baseline", baseline == null ? "" : baseline.getContent(), "target", target.getContent()));
    }

    public void markRunning(Long reportId) {
        reportMapper.update(null, new UpdateWrapper<ProseRevisionImpactReportEntity>().eq("id", reportId)
                .in("report_status", STATUS_QUEUED, STATUS_FAILED).set("report_status", STATUS_RUNNING)
                .set("error_code", null).setSql("version = version + 1"));
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void complete(Long reportId, ImpactAnalysis analysis, Long modelCallId) {
        ProseRevisionImpactReportEntity report = requireFreshReport(reportId);
        ChapterProseRevisionEntity target = revisionMapper.selectById(report.getTargetRevisionId());
        ImpactAnalysis validated = validate(analysis, target.getContent(), report.getWorkId(), report.getChapterId());
        changeMapper.delete(new LambdaQueryWrapper<ProseRevisionFactChangeEntity>()
                .eq(ProseRevisionFactChangeEntity::getReportId, reportId));
        assetMapper.delete(new LambdaQueryWrapper<ProseRevisionImpactedAssetEntity>()
                .eq(ProseRevisionImpactedAssetEntity::getReportId, reportId));
        for (FactChange change : validated.changes()) {
            ProseRevisionFactChangeEntity entity = new ProseRevisionFactChangeEntity();
            entity.setReportId(reportId); entity.setChangeKey(change.changeKey()); entity.setFactType(change.factType());
            entity.setEpistemicStatus(change.epistemicStatus()); entity.setChangeKind(change.changeKind());
            entity.setImpactScope(change.impactScope()); entity.setEvidenceText(change.evidenceText());
            entity.setEvidenceStartOffset(change.evidenceStartOffset()); entity.setEvidenceEndOffset(change.evidenceEndOffset());
            entity.setConfidence(change.confidence()); entity.setDirectDependency(Boolean.TRUE.equals(change.directDependency()) ? 1 : 0);
            entity.setAffectedChapterIdsJson(json(change.affectedChapterIds()));
            entity.setExplanation(change.explanation()); entity.setDeleted(0); entity.setVersion(0); changeMapper.insert(entity);
        }
        persistAssets(report, validated);
        boolean blocking = requiresHuman(validated);
        int updated = reportMapper.update(null, new UpdateWrapper<ProseRevisionImpactReportEntity>()
                .eq("id", reportId).eq("version", report.getVersion()).in("report_status", STATUS_RUNNING, STATUS_QUEUED)
                .set("report_status", STATUS_READY).set("impact_scope", validated.impactScope())
                .set("blocking", blocking ? 1 : 0).set("summary_json", json(Map.of("summary", validated.summary())))
                .set("model_call_id", modelCallId).set("error_code", null).setSql("version = version + 1"));
        if (updated != 1) { throw conflict(COMPLETE_CONFLICT_MESSAGE); }
    }

    public void fail(Long reportId, Exception exception) {
        reportMapper.update(null, new UpdateWrapper<ProseRevisionImpactReportEntity>().eq("id", reportId)
                .in("report_status", STATUS_QUEUED, STATUS_RUNNING).set("report_status", STATUS_FAILED)
                .set("blocking", 1).set("error_code", errorCode(exception)).setSql("version = version + 1"));
    }

    public ImpactAnalysis validate(ImpactAnalysis analysis, String targetContent) {
        return validate(analysis, targetContent, null, null);
    }

    ImpactAnalysis validate(ImpactAnalysis analysis, String targetContent, Long workId, Long currentChapterId) {
        if (analysis == null) { throw invalid(EMPTY_ANALYSIS_MESSAGE); }
        if (!SCOPES.contains(analysis.impactScope())) { throw invalid(INVALID_SCOPE_MESSAGE); }
        if (!StringUtils.hasText(analysis.summary())) { throw invalid(EMPTY_SUMMARY_MESSAGE); }
        if (analysis.changes() == null || analysis.changes().size() > MAX_FACT_CHANGE_COUNT) {
            throw invalid("事实变化列表缺失或超限");
        }
        Set<String> keys = new LinkedHashSet<>();
        List<FactChange> normalizedChanges = new ArrayList<>();
        ChapterScope chapterScope = workId == null ? null : chapterScope(workId, currentChapterId);
        for (int index = 0; index < analysis.changes().size(); index++) {
            FactChange change = analysis.changes().get(index);
            if (change == null || !StringUtils.hasText(change.changeKey()) || !keys.add(change.changeKey())
                    || !FACT_TYPES.contains(change.factType()) || !EPISTEMIC.contains(change.epistemicStatus())
                    || !CHANGE_KINDS.contains(change.changeKind()) || !SCOPES.contains(change.impactScope())
                    || !Objects.equals(analysis.impactScope(), change.impactScope())
                    || change.confidence() == null || change.confidence().compareTo(BigDecimal.ZERO) < 0
                    || change.confidence().compareTo(BigDecimal.ONE) > 0
                    || !StringUtils.hasText(change.explanation())
                    || change.affectedChapterIds() == null) {
                throw invalid("影响分析证据未通过正文边界校验");
            }
            FactChange normalizedChange = normalizeEvidence(change, targetContent, index);
            normalizedChanges.add(normalizedChange);
            if (chapterScope != null) { validateAffectedChapters(normalizedChange, chapterScope); }
        }
        if (Set.of(SCOPE_NONE, SCOPE_LANGUAGE_ONLY).contains(analysis.impactScope())
                && !analysis.changes().isEmpty()) {
            throw invalid("无事实变化或纯语言调整不得携带事实变化");
        }
        return new ImpactAnalysis(analysis.impactScope(), analysis.summary(), List.copyOf(normalizedChanges));
    }

    private FactChange normalizeEvidence(FactChange change, String targetContent, int index) {
        String basePath = "changes[" + index + "].evidence";
        if (!StringUtils.hasText(change.evidenceText()) || targetContent == null) {
            throw new ProseImpactContractException("missing_evidence", basePath + "Text");
        }
        Integer startOffset = change.evidenceStartOffset();
        Integer endOffset = change.evidenceEndOffset();
        if (isExactEvidenceRange(targetContent, change.evidenceText(), startOffset, endOffset)) {
            return change;
        }
        int exactStart = targetContent.indexOf(change.evidenceText());
        if (exactStart < 0) {
            throw new ProseImpactContractException("invalid_reference", basePath + "Text");
        }
        if (targetContent.indexOf(change.evidenceText(), exactStart + 1) >= 0) {
            throw new ProseImpactContractException("ambiguous_reference", basePath + "Text");
        }
        int exactEnd = exactStart + change.evidenceText().length();
        return new FactChange(change.changeKey(), change.factType(), change.epistemicStatus(), change.changeKind(),
                change.impactScope(), change.evidenceText(), exactStart, exactEnd, change.confidence(),
                change.directDependency(), change.explanation(), change.affectedChapterIds());
    }

    private boolean isExactEvidenceRange(String targetContent, String evidenceText,
            Integer startOffset, Integer endOffset) {
        return startOffset != null && endOffset != null && startOffset >= 0
                && endOffset <= targetContent.length() && startOffset < endOffset
                && Objects.equals(targetContent.substring(startOffset, endOffset), evidenceText);
    }

    boolean requiresHuman(ImpactAnalysis analysis) {
        return Set.of(SCOPE_UNKNOWN, SCOPE_WORK).contains(analysis.impactScope())
                || analysis.changes().stream().anyMatch(item -> "faction_rule".equals(item.factType()))
                || analysis.changes().stream()
                        .anyMatch(item -> item.confidence().compareTo(new BigDecimal("0.60")) < 0);
    }

    private ChapterScope chapterScope(Long workId, Long currentChapterId) {
        List<ChapterEntity> queried = chapterMapper.selectList(new LambdaQueryWrapper<ChapterEntity>()
                .eq(ChapterEntity::getWorkId, workId).eq(ChapterEntity::getDeleted, 0)
                .orderByAsc(ChapterEntity::getChapterNo).orderByAsc(ChapterEntity::getId));
        List<ChapterEntity> chapters = queried.stream().filter(item -> Objects.equals(workId, item.getWorkId())
                && !Integer.valueOf(1).equals(item.getDeleted())).toList();
        int currentIndex = java.util.stream.IntStream.range(0, chapters.size())
                .filter(index -> Objects.equals(chapters.get(index).getId(), currentChapterId))
                .findFirst().orElse(-1);
        if (currentIndex < 0) { throw invalid(CHAPTER_OUTSIDE_WORK_MESSAGE); }
        Set<Long> all = chapters.stream().map(ChapterEntity::getId).collect(java.util.stream.Collectors.toSet());
        Set<Long> adjacent = new LinkedHashSet<>();
        if (currentIndex > 0) { adjacent.add(chapters.get(currentIndex - 1).getId()); }
        adjacent.add(currentChapterId);
        if (currentIndex + 1 < chapters.size()) { adjacent.add(chapters.get(currentIndex + 1).getId()); }
        return new ChapterScope(currentChapterId, Set.copyOf(all), Set.copyOf(adjacent));
    }

    private void validateAffectedChapters(FactChange change, ChapterScope chapterScope) {
        List<Long> affected = change.affectedChapterIds();
        if (affected.stream().anyMatch(Objects::isNull)) {
            throw invalid("受影响章节必须是当前作品内不重复的有效章节");
        }
        if (new LinkedHashSet<>(affected).size() != affected.size()) {
            throw invalid("受影响章节必须是当前作品内不重复的有效章节");
        }
        if (!chapterScope.allChapterIds().containsAll(affected)) {
            throw invalid("受影响章节必须是当前作品内不重复的有效章节");
        }
        if (SCOPE_LOCAL.equals(change.impactScope())
                && !affected.equals(List.of(chapterScope.currentChapterId()))) {
            throw invalid("local 影响只能指向当前章节");
        }
        if (SCOPE_ADJACENT.equals(change.impactScope())) {
            if (affected.isEmpty() || !affected.contains(chapterScope.currentChapterId())) {
                throw invalid("adjacent 影响只能指向当前章及相邻章节");
            }
            if (!chapterScope.adjacentChapterIds().containsAll(affected)) {
                throw invalid("adjacent 影响只能指向当前章及相邻章节");
            }
        }
        if (SCOPE_CROSS_CHAPTER.equals(change.impactScope())) {
            if (affected.isEmpty() || !affected.contains(chapterScope.currentChapterId())) {
                throw invalid("cross_chapter 必须明确当前章之外的引用章节");
            }
            if (affected.stream().allMatch(id -> Objects.equals(id, chapterScope.currentChapterId()))) {
                throw invalid("cross_chapter 必须明确当前章之外的引用章节");
            }
        }
    }

    public ImpactAnalysis validateForReport(Long reportId, ImpactAnalysis analysis) {
        ProseRevisionImpactReportEntity report = requireFreshReport(reportId);
        ChapterProseRevisionEntity target = revisionMapper.selectById(report.getTargetRevisionId());
        return validate(analysis, target.getContent(), report.getWorkId(), report.getChapterId());
    }

    @Override public List<String> workspaceBlockingItems(Long workId, Long workspaceId) {
        return workspaceBlockingDetails(workId, workspaceId).stream().map(this::blockingText).toList();
    }

    private List<ImpactBlockingItem> workspaceBlockingDetails(Long workId, Long workspaceId) {
        List<ImpactBlockingItem> blocking = new ArrayList<>();
        for (WorkRevisionWorkspaceChapterEntity entry : workspaceEntries(workId, workspaceId)) {
            ProseRevisionImpactReportEntity report = latestEntity(workId, workspaceId, entry.getProseRevisionId());
            if (report == null) {
                blocking.add(new ImpactBlockingItem("impact_report_missing", entry.getProseRevisionId(),
                        null, null, null, null));
            } else if (isStale(report)) {
                blocking.add(new ImpactBlockingItem("impact_report_stale", entry.getProseRevisionId(),
                        report.getId(), null, null, STATUS_STALE));
            } else if (!STATUS_READY.equals(report.getReportStatus())) {
                blocking.add(new ImpactBlockingItem("impact_report_not_ready", entry.getProseRevisionId(),
                        report.getId(), null, null, report.getReportStatus()));
            } else if (Integer.valueOf(1).equals(report.getBlocking())) {
                blocking.add(new ImpactBlockingItem("impact_report_blocking", entry.getProseRevisionId(),
                        report.getId(), null, null, report.getImpactScope()));
            } else if (!Set.of(SCOPE_NONE, SCOPE_LANGUAGE_ONLY).contains(report.getImpactScope())) {
                addKnowledgeBlockingItems(blocking, entry.getProseRevisionId(), report);
            }
        }
        return List.copyOf(blocking);
    }

    @Override public WorkspaceImpactSummary workspaceSummary(Long workId, Long workspaceId) {
        List<ProseRevisionImpactReportEntity> reports = workspaceEntries(workId, workspaceId).stream()
                .map(item -> latestEntity(workId, workspaceId, item.getProseRevisionId())).filter(Objects::nonNull).toList();
        List<ImpactBlockingItem> blockingItems = workspaceBlockingDetails(workId, workspaceId);
        return new WorkspaceImpactSummary(reports.size(), (int) reports.stream()
                .filter(r -> STATUS_READY.equals(r.getReportStatus()) && !isStale(r)).count(),
                blockingItems.size(),
                (int) reports.stream().filter(r -> STATUS_FAILED.equals(r.getReportStatus())).count(),
                (int) reports.stream().filter(this::isStale).count(),
                reports.stream().map(ProseRevisionImpactReportEntity::getId).toList(),
                reports.stream().map(ProseRevisionImpactReportEntity::getImpactScope)
                        .filter(Objects::nonNull).distinct().toList(), blockingItems);
    }

    private void addKnowledgeBlockingItems(List<ImpactBlockingItem> blocking, Long revisionId,
            ProseRevisionImpactReportEntity report) {
        List<StoryKnowledgeExtractionBatchEntity> batches = batchMapper.selectList(
                new LambdaQueryWrapper<StoryKnowledgeExtractionBatchEntity>()
                        .eq(StoryKnowledgeExtractionBatchEntity::getWorkId, report.getWorkId())
                        .eq(StoryKnowledgeExtractionBatchEntity::getChapterId, report.getChapterId())
                        .eq(StoryKnowledgeExtractionBatchEntity::getSourceProseRevisionId, revisionId)
                        .eq(StoryKnowledgeExtractionBatchEntity::getDeleted, 0)
                        .orderByDesc(StoryKnowledgeExtractionBatchEntity::getId));
        StoryKnowledgeExtractionBatchEntity latestBatch = batches.isEmpty() ? null : batches.get(0);
        if (latestBatch == null) {
            blocking.add(new ImpactBlockingItem("knowledge_batch_missing", revisionId,
                    report.getId(), null, null, null));
            return;
        }
        if (!KNOWLEDGE_READY.equals(latestBatch.getBatchStatus())) {
            blocking.add(new ImpactBlockingItem("knowledge_batch_not_ready", revisionId,
                    report.getId(), latestBatch.getId(), null, latestBatch.getBatchStatus()));
            return;
        }
        List<StoryKnowledgeCandidateEntity> candidates = candidateMapper.selectList(
                new LambdaQueryWrapper<StoryKnowledgeCandidateEntity>()
                        .eq(StoryKnowledgeCandidateEntity::getBatchId, latestBatch.getId())
                        .eq(StoryKnowledgeCandidateEntity::getDeleted, 0)
                        .orderByAsc(StoryKnowledgeCandidateEntity::getId));
        if (latestBatch.getCandidateCount() != null
                && latestBatch.getCandidateCount() != candidates.size()) {
            blocking.add(new ImpactBlockingItem("knowledge_candidate_count_mismatch", revisionId,
                    report.getId(), latestBatch.getId(), null, String.valueOf(latestBatch.getCandidateCount())));
        }
        candidates.stream().filter(item -> !KNOWLEDGE_TERMINAL_STATUSES.contains(item.getCandidateStatus()))
                .map(item -> new ImpactBlockingItem("knowledge_candidate_not_handled", revisionId,
                        report.getId(), latestBatch.getId(), item.getId(), item.getCandidateStatus()))
                .forEach(blocking::add);
    }

    private String blockingText(ImpactBlockingItem item) {
        return switch (item.code()) {
            case "impact_report_missing", "knowledge_batch_missing" -> item.code() + ":" + item.revisionId();
            case "impact_report_stale", "impact_report_not_ready", "impact_report_blocking" ->
                    item.code() + ":" + item.reportId();
            case "knowledge_candidate_not_handled" -> item.code() + ":" + item.candidateId() + ":" + item.status();
            default -> item.code() + ":" + item.batchId() + ":" + item.status();
        };
    }

    @Override
    public void activateRelease(Long workId, Long releaseId, Long previousReleaseId, Long rollbackTargetReleaseId) {
        List<StoryReleaseKnowledgeSourceEntity> next = rollbackTargetReleaseId == null
                ? sourcesFromReleaseRevisions(workId, releaseId) : sourcesFromHistoricalRelease(workId, releaseId, rollbackTargetReleaseId);
        next.forEach(knowledgeSourceMapper::insert);
        if (previousReleaseId != null) {
            knowledgeSourceMapper.update(null, new UpdateWrapper<StoryReleaseKnowledgeSourceEntity>()
                    .eq("work_id", workId).eq("release_id", previousReleaseId).eq("active_marker", 1)
                    .set("active_marker", null).set("source_status", "superseded").setSql("version = version + 1"));
        }
        if (!next.isEmpty()) {
            knowledgeSourceMapper.update(null, new UpdateWrapper<StoryReleaseKnowledgeSourceEntity>()
                    .eq("release_id", releaseId).eq("source_status", "preparing")
                    .set("source_status", "current").set("active_marker", 1).setSql("version = version + 1"));
        }
        propagateReleaseImpacts(workId, releaseId);
    }

    private void propagateReleaseImpacts(Long workId, Long releaseId) {
        List<StoryReleaseChapterEntity> mappings = releaseChapterMapper.selectList(
                new LambdaQueryWrapper<StoryReleaseChapterEntity>().eq(StoryReleaseChapterEntity::getReleaseId, releaseId)
                        .eq(StoryReleaseChapterEntity::getDeleted, 0));
        for (StoryReleaseChapterEntity mapping : mappings) {
            ProseRevisionImpactReportEntity report = latestReady(workId, mapping.getProseRevisionId());
            if (report == null || Integer.valueOf(1).equals(report.getBlocking())
                    || Set.of(SCOPE_NONE, SCOPE_LANGUAGE_ONLY, SCOPE_WORK, SCOPE_UNKNOWN)
                            .contains(report.getImpactScope())) {
                continue;
            }
            for (Long chapterId : reportAffectedChapterIds(report.getId())) {
                sourceChainService.markNeedsReview(chapterId, "prose-release:" + releaseId + ":report:" + report.getId(),
                        List.of("published_prose_fact_changed", "impact_scope_" + report.getImpactScope()));
            }
        }
    }

    private List<StoryReleaseKnowledgeSourceEntity> sourcesFromHistoricalRelease(Long workId, Long releaseId, Long target) {
        return knowledgeSourceMapper.selectList(new LambdaQueryWrapper<StoryReleaseKnowledgeSourceEntity>()
                .eq(StoryReleaseKnowledgeSourceEntity::getWorkId, workId)
                .eq(StoryReleaseKnowledgeSourceEntity::getReleaseId, target)
                .eq(StoryReleaseKnowledgeSourceEntity::getDeleted, 0)).stream()
                .map(source -> copySource(source, releaseId)).toList();
    }

    private List<StoryReleaseKnowledgeSourceEntity> sourcesFromReleaseRevisions(Long workId, Long releaseId) {
        Map<String, StoryReleaseKnowledgeSourceEntity> deduplicated = new LinkedHashMap<>();
        List<StoryReleaseChapterEntity> mappings = releaseChapterMapper.selectList(
                new LambdaQueryWrapper<StoryReleaseChapterEntity>().eq(StoryReleaseChapterEntity::getReleaseId, releaseId)
                        .eq(StoryReleaseChapterEntity::getDeleted, 0));
        for (StoryReleaseChapterEntity mapping : mappings) {
            List<StoryKnowledgeExtractionBatchEntity> batches = batchMapper.selectList(
                    new LambdaQueryWrapper<StoryKnowledgeExtractionBatchEntity>()
                            .eq(StoryKnowledgeExtractionBatchEntity::getSourceProseRevisionId, mapping.getProseRevisionId())
                            .eq(StoryKnowledgeExtractionBatchEntity::getDeleted, 0));
            if (batches.isEmpty()) { continue; }
            List<Long> ids = batches.stream().map(StoryKnowledgeExtractionBatchEntity::getId).toList();
            candidateMapper.selectList(new LambdaQueryWrapper<StoryKnowledgeCandidateEntity>()
                    .in(StoryKnowledgeCandidateEntity::getBatchId, ids)
                    .eq(StoryKnowledgeCandidateEntity::getCandidateStatus, "confirmed")
                    .eq(StoryKnowledgeCandidateEntity::getDeleted, 0)).stream()
                    .filter(candidate -> candidate.getConfirmedTargetId() != null && candidate.getConfirmedTargetType() != null)
                    .map(candidate -> source(workId, releaseId, mapping, candidate))
                    .forEach(source -> deduplicated.merge(knowledgeKey(source), source,
                            (left, right) -> left.getSourceCandidateId() <= right.getSourceCandidateId() ? left : right));
        }
        return deduplicated.values().stream()
                .sorted(Comparator.comparing(this::knowledgeKey)).toList();
    }

    private String knowledgeKey(StoryReleaseKnowledgeSourceEntity source) {
        return source.getKnowledgeType() + ":" + source.getKnowledgeId();
    }

    private StoryReleaseKnowledgeSourceEntity source(Long workId, Long releaseId, StoryReleaseChapterEntity mapping,
            StoryKnowledgeCandidateEntity candidate) {
        StoryReleaseKnowledgeSourceEntity entity = new StoryReleaseKnowledgeSourceEntity();
        entity.setWorkId(workId); entity.setReleaseId(releaseId); entity.setChapterId(mapping.getChapterId());
        entity.setProseRevisionId(mapping.getProseRevisionId()); entity.setKnowledgeType(candidate.getConfirmedTargetType());
        entity.setKnowledgeId(candidate.getConfirmedTargetId()); entity.setSourceCandidateId(candidate.getId());
        entity.setSourceStatus("preparing"); entity.setDeleted(0); entity.setVersion(0); return entity;
    }

    private StoryReleaseKnowledgeSourceEntity copySource(StoryReleaseKnowledgeSourceEntity source, Long releaseId) {
        StoryReleaseKnowledgeSourceEntity copy = new StoryReleaseKnowledgeSourceEntity();
        copy.setWorkId(source.getWorkId()); copy.setReleaseId(releaseId); copy.setChapterId(source.getChapterId());
        copy.setProseRevisionId(source.getProseRevisionId()); copy.setKnowledgeType(source.getKnowledgeType());
        copy.setKnowledgeId(source.getKnowledgeId()); copy.setSourceCandidateId(source.getSourceCandidateId());
        copy.setSourceStatus("preparing"); copy.setDeleted(0); copy.setVersion(0); return copy;
    }

    private void persistAssets(ProseRevisionImpactReportEntity report, ImpactAnalysis analysis) {
        if (Set.of(SCOPE_NONE, SCOPE_LANGUAGE_ONLY).contains(analysis.impactScope())) { return; }
        List<Long> affectedChapters = analysis.changes().stream().flatMap(item -> item.affectedChapterIds().stream())
                .distinct().sorted().toList();
        if (affectedChapters.isEmpty()) { return; }
        List<ChapterAssetSourceSnapshotEntity> snapshots = sourceSnapshotMapper.selectList(
                new LambdaQueryWrapper<ChapterAssetSourceSnapshotEntity>()
                        .eq(ChapterAssetSourceSnapshotEntity::getWorkId, report.getWorkId())
                        .in(ChapterAssetSourceSnapshotEntity::getChapterId, affectedChapters)
                        .eq(ChapterAssetSourceSnapshotEntity::getDeleted, 0));
        for (ChapterAssetSourceSnapshotEntity snapshot : snapshots) {
            ProseRevisionImpactedAssetEntity entity = new ProseRevisionImpactedAssetEntity();
            entity.setReportId(report.getId()); entity.setChapterId(snapshot.getChapterId());
            entity.setAssetType(snapshot.getAssetType()); entity.setAssetId(snapshot.getAssetId());
            entity.setDependencyType(Objects.equals(snapshot.getChapterId(), report.getChapterId()) ? "direct" : "indirect");
            entity.setValidityStatus("needs_review");
            entity.setReasonCode("published_prose_fact_changed"); entity.setDeleted(0); entity.setVersion(0); assetMapper.insert(entity);
        }
    }

    private List<Long> reportAffectedChapterIds(Long reportId) {
        return changeMapper.selectList(new LambdaQueryWrapper<ProseRevisionFactChangeEntity>()
                .eq(ProseRevisionFactChangeEntity::getReportId, reportId)
                .eq(ProseRevisionFactChangeEntity::getDeleted, 0)).stream()
                .flatMap(item -> parseChapterIds(item.getAffectedChapterIdsJson()).stream())
                .distinct().sorted().toList();
    }

    private ProseRevisionImpactReportEntity latestReady(Long workId, Long revisionId) {
        ProseRevisionImpactReportEntity report = reportMapper.selectOne(new LambdaQueryWrapper<ProseRevisionImpactReportEntity>()
                .eq(ProseRevisionImpactReportEntity::getWorkId, workId)
                .eq(ProseRevisionImpactReportEntity::getTargetRevisionId, revisionId)
                .eq(ProseRevisionImpactReportEntity::getReportStatus, STATUS_READY)
                .eq(ProseRevisionImpactReportEntity::getDeleted, 0).orderByDesc(ProseRevisionImpactReportEntity::getId).last("LIMIT 1"));
        return report != null && !isStale(report) ? report : null;
    }

    private ProseRevisionImpactReportEntity latestEntity(Long workId, Long workspaceId, Long revisionId) {
        return reportMapper.selectOne(new LambdaQueryWrapper<ProseRevisionImpactReportEntity>()
                .eq(ProseRevisionImpactReportEntity::getWorkId, workId)
                .eq(ProseRevisionImpactReportEntity::getWorkspaceId, workspaceId)
                .eq(ProseRevisionImpactReportEntity::getTargetRevisionId, revisionId)
                .eq(ProseRevisionImpactReportEntity::getDeleted, 0).orderByDesc(ProseRevisionImpactReportEntity::getId).last("LIMIT 1"));
    }

    private List<WorkRevisionWorkspaceChapterEntity> workspaceEntries(Long workId, Long workspaceId) {
        requireWorkspace(workId, workspaceId);
        return workspaceChapterMapper.selectList(new LambdaQueryWrapper<WorkRevisionWorkspaceChapterEntity>()
                .eq(WorkRevisionWorkspaceChapterEntity::getWorkspaceId, workspaceId)
                .eq(WorkRevisionWorkspaceChapterEntity::getDeleted, 0));
    }

    private boolean workspaceContains(Long workspaceId, Long chapterId, Long revisionId) {
        return workspaceChapterMapper.selectCount(new LambdaQueryWrapper<WorkRevisionWorkspaceChapterEntity>()
                .eq(WorkRevisionWorkspaceChapterEntity::getWorkspaceId, workspaceId)
                .eq(WorkRevisionWorkspaceChapterEntity::getChapterId, chapterId)
                .eq(WorkRevisionWorkspaceChapterEntity::getProseRevisionId, revisionId)
                .eq(WorkRevisionWorkspaceChapterEntity::getDeleted, 0)) == 1;
    }

    private WorkRevisionWorkspaceEntity requireWorkspace(Long workId, Long workspaceId) {
        WorkRevisionWorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || Integer.valueOf(1).equals(workspace.getDeleted()) || !Objects.equals(workId, workspace.getWorkId())) {
            throw new BusinessException(ErrorCode.REVISION_WORKSPACE_NOT_FOUND, "作品修订工作区不存在");
        }
        return workspace;
    }

    private ChapterProseRevisionEntity requireRevision(Long workId, Long chapterId, Long revisionId) {
        ChapterProseRevisionEntity revision = revisionMapper.selectById(revisionId);
        if (revision == null || Integer.valueOf(1).equals(revision.getDeleted()) || !Objects.equals(workId, revision.getWorkId())
                || !Objects.equals(chapterId, revision.getChapterId())) {
            throw new BusinessException(ErrorCode.PROSE_REVISION_NOT_FOUND, "正文 revision 不存在");
        }
        return revision;
    }

    private BaselineBinding resolveBaseline(Long workId, Long chapterId, WorkRevisionWorkspaceEntity workspace,
            Long requestedBaselineRevisionId) {
        Long baselineReleaseId;
        if (workspace != null) {
            baselineReleaseId = workspace.getBaselineReleaseId();
        } else {
            WorkEntity work = workMapper.selectById(workId);
            if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
                throw conflict("影响报告所属作品不存在");
            }
            baselineReleaseId = work.getCurrentStoryReleaseId();
        }
        if (baselineReleaseId == null) {
            if (requestedBaselineRevisionId != null) {
                throw conflict("首次 Story Release 前不得指定任意 baseline revision");
            }
            return new BaselineBinding(null, null);
        }
        StoryReleaseChapterEntity mapping = releaseChapterMapper.selectOne(
                new LambdaQueryWrapper<StoryReleaseChapterEntity>()
                        .eq(StoryReleaseChapterEntity::getReleaseId, baselineReleaseId)
                        .eq(StoryReleaseChapterEntity::getWorkId, workId)
                        .eq(StoryReleaseChapterEntity::getChapterId, chapterId)
                        .eq(StoryReleaseChapterEntity::getDeleted, 0));
        if (mapping == null) {
            if (requestedBaselineRevisionId != null) {
                throw conflict("baseline Story Release 不含新增章节，不能指定任意 baseline revision");
            }
            return new BaselineBinding(baselineReleaseId, null);
        }
        if (requestedBaselineRevisionId != null
                && !Objects.equals(requestedBaselineRevisionId, mapping.getProseRevisionId())) {
            throw conflict("请求 baseline revision 与 Story Release 章节映射不一致");
        }
        return new BaselineBinding(baselineReleaseId,
                requireRevision(workId, chapterId, mapping.getProseRevisionId()));
    }

    private ProseRevisionImpactReportEntity requireReport(Long workId, Long reportId) {
        ProseRevisionImpactReportEntity report = reportMapper.selectById(reportId);
        boolean missing = report == null || Integer.valueOf(1).equals(report == null ? null : report.getDeleted());
        boolean wrongWork = report != null && workId != null && !Objects.equals(workId, report.getWorkId());
        if (missing || wrongWork) { throw notFound(); }
        return report;
    }

    private ProseRevisionImpactReportEntity requireReportIdentity(Long workId, Long chapterId,
            Long targetRevisionId, Long reportId) {
        ProseRevisionImpactReportEntity report = requireReport(workId, reportId);
        if (!Objects.equals(chapterId, report.getChapterId())) {
            throw notFound();
        }
        if (!Objects.equals(targetRevisionId, report.getTargetRevisionId())) {
            throw notFound();
        }
        return report;
    }

    private ProseRevisionImpactReportEntity requireFreshReport(Long reportId) {
        ProseRevisionImpactReportEntity report = requireReport(null, reportId);
        if (isStale(report)) {
            throw conflict("来源图、正文或分析器已变化，旧影响报告不可继续使用");
        }
        return report;
    }

    private ProseRevisionImpactReportEntity byIdempotency(Long workId, String key) {
        return reportMapper.selectOne(new LambdaQueryWrapper<ProseRevisionImpactReportEntity>()
                .eq(ProseRevisionImpactReportEntity::getWorkId, workId)
                .eq(ProseRevisionImpactReportEntity::getIdempotencyKey, key)
                .eq(ProseRevisionImpactReportEntity::getDeleted, 0));
    }

    private String fingerprint(Long baselineReleaseId, ChapterProseRevisionEntity baseline,
            ChapterProseRevisionEntity target, Long workspaceId, String analyzerVersion,
            String sourceGraphFingerprint) {
        return hash("release=" + baselineReleaseId + "|baseline="
                + (baseline == null ? "none" : baseline.getId() + ":" + baseline.getContentHash())
                + "|target=" + target.getId() + ":" + target.getContentHash()
                + "|workspace=" + workspaceId + "|analyzer=" + analyzerVersion
                + "|sourceGraph=" + sourceGraphFingerprint);
    }

    String sourceGraphFingerprint(Long workId, Long chapterId) {
        List<List<Object>> graph = sourceSnapshotMapper.selectList(
                new LambdaQueryWrapper<ChapterAssetSourceSnapshotEntity>()
                        .eq(ChapterAssetSourceSnapshotEntity::getWorkId, workId)
                        .eq(ChapterAssetSourceSnapshotEntity::getDeleted, 0)
                        .orderByAsc(ChapterAssetSourceSnapshotEntity::getChapterId)
                        .orderByAsc(ChapterAssetSourceSnapshotEntity::getAssetType)
                        .orderByAsc(ChapterAssetSourceSnapshotEntity::getAssetId)
                        .orderByAsc(ChapterAssetSourceSnapshotEntity::getId)).stream()
                .map(item -> List.<Object>of(value(item.getId()), value(item.getChapterId()),
                        value(item.getAssetType()), value(item.getAssetId()), value(item.getAssetVersion()),
                        value(item.getSourceConsensusVersionId()),
                        value(item.getSourceNarrativePlanVersionId()), value(item.getSourceOutlineId()),
                        value(item.getSourceOutlineRevision()), value(item.getSourceScenePlanVersionId()),
                        value(item.getSourceContextSnapshotId()), value(item.getSourceContentHash())))
                .toList();
        return hash(json(List.of(chapterId, graph)));
    }

    String currentInputFingerprint(ProseRevisionImpactReportEntity report) {
        return currentInputFingerprint(report, sourceGraphFingerprint(report.getWorkId(), report.getChapterId()));
    }

    private String currentInputFingerprint(ProseRevisionImpactReportEntity report, String graphFingerprint) {
        ChapterProseRevisionEntity target = revisionMapper.selectById(report.getTargetRevisionId());
        ChapterProseRevisionEntity baseline = report.getBaselineRevisionId() == null ? null
                : revisionMapper.selectById(report.getBaselineRevisionId());
        if (target == null) {
            return "missing-revision";
        }
        if (report.getBaselineRevisionId() != null && baseline == null) {
            return "missing-revision";
        }
        return fingerprint(report.getBaselineReleaseId(), baseline, target, report.getWorkspaceId(),
                ANALYZER_VERSION, graphFingerprint);
    }

    private boolean isStale(ProseRevisionImpactReportEntity report) {
        String graphFingerprint = sourceGraphFingerprint(report.getWorkId(), report.getChapterId());
        return !Objects.equals(ANALYZER_VERSION, report.getAnalyzerVersion())
                || !Objects.equals(graphFingerprint, report.getSourceGraphFingerprint())
                || !Objects.equals(currentInputFingerprint(report, graphFingerprint), report.getInputFingerprint());
    }

    private ReportView view(ProseRevisionImpactReportEntity report) {
        List<FactChange> changes = changeMapper.selectList(new LambdaQueryWrapper<ProseRevisionFactChangeEntity>()
                .eq(ProseRevisionFactChangeEntity::getReportId, report.getId())
                .eq(ProseRevisionFactChangeEntity::getDeleted, 0).orderByAsc(ProseRevisionFactChangeEntity::getId)).stream()
                .map(item -> new FactChange(item.getChangeKey(), item.getFactType(), item.getEpistemicStatus(),
                        item.getChangeKind(), item.getImpactScope(), item.getEvidenceText(), item.getEvidenceStartOffset(),
                        item.getEvidenceEndOffset(), item.getConfidence(), item.getDirectDependency() == 1,
                        item.getExplanation(), parseChapterIds(item.getAffectedChapterIdsJson()))).toList();
        List<ImpactedAssetView> assets = assetMapper.selectList(new LambdaQueryWrapper<ProseRevisionImpactedAssetEntity>()
                .eq(ProseRevisionImpactedAssetEntity::getReportId, report.getId())
                .eq(ProseRevisionImpactedAssetEntity::getDeleted, 0).orderByAsc(ProseRevisionImpactedAssetEntity::getId)).stream()
                .map(item -> new ImpactedAssetView(item.getChapterId(), item.getAssetType(), item.getAssetId(),
                        item.getDependencyType(), item.getValidityStatus(), item.getReasonCode())).toList();
        String summary = null;
        if (report.getSummaryJson() != null) {
            try { summary = objectMapper.readValue(report.getSummaryJson(), new TypeReference<Map<String, String>>() { }).get("summary"); }
            catch (Exception exception) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "影响报告摘要损坏", exception); }
        }
        boolean stale = isStale(report);
        return new ReportView(report.getId(), report.getWorkId(), report.getChapterId(), report.getWorkspaceId(),
                report.getBaselineRevisionId(), report.getTargetRevisionId(), report.getBaselineReleaseId(),
                report.getAgentRunId(), report.getModelCallId(), report.getInputFingerprint(),
                report.getSourceGraphFingerprint(), report.getAnalyzerVersion(),
                stale ? STATUS_STALE : report.getReportStatus(), report.getImpactScope(),
                stale || Integer.valueOf(1).equals(report.getBlocking()), summary,
                stale ? "source_or_analyzer_changed" : report.getErrorCode(), changes, assets,
                report.getVersion(), report.getGmtCreate(), report.getGmtModified());
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "影响报告无法序列化", exception); }
    }
    private List<Long> parseChapterIds(String json) {
        if (!StringUtils.hasText(json)) { return List.of(); }
        try { return objectMapper.readValue(json, new TypeReference<List<Long>>() { }); }
        catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "受影响章节证据损坏", exception);
        }
    }
    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private Object value(Object value) { return value == null ? "" : value; }
    String errorCode(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ProseImpactContractException contractException) {
                return "impact_output_" + contractException.category();
            }
            if (current instanceof BusinessException business
                    && business.getErrorCode() == ErrorCode.PROSE_IMPACT_REPORT_INVALID) {
                return "invalid_model_output";
            }
            String name = current.getClass().getSimpleName().toLowerCase();
            if (name.contains("timeout")) { return "provider_timeout"; }
            current = current.getCause();
        }
        return "provider_failed";
    }
    private BusinessException notFound() { return new BusinessException(ErrorCode.PROSE_IMPACT_REPORT_NOT_FOUND, "正文影响报告不存在"); }
    private BusinessException conflict(String message) { return new BusinessException(ErrorCode.PROSE_IMPACT_REPORT_CONFLICT, message); }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.PROSE_IMPACT_REPORT_INVALID, message); }
    private BusinessException badRequest(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private record BaselineBinding(Long releaseId, ChapterProseRevisionEntity revision) { }
    private record ChapterScope(Long currentChapterId, Set<Long> allChapterIds, Set<Long> adjacentChapterIds) { }
}
