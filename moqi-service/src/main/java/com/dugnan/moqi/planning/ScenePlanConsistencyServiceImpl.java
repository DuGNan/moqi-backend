package com.dugnan.moqi.planning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusCodec;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.Decision;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusDocument;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusValidator;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.BriefView;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContentCodec;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.CheckRequest;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.ConsistencyFinding;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.ConsistencyReportView;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.DiscussionProposalRequest;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.FindingSourceRef;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.RetryRequest;
import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;
import com.dugnan.moqi.planning.entity.ScenePlanConsistencyReportEntity;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanConsistencyReportMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 实现场景规划检查快照、确定性规则、发布门禁和共识草稿回流。
 */
@Service
public class ScenePlanConsistencyServiceImpl implements ScenePlanConsistencyService {
    static final String WORKFLOW_TYPE = "scene_plan_consistency_v1";
    private static final String LOCAL_USER = "local-user";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_STALE = "stale";
    private static final String STATUS_CANCELED = "canceled";
    private static final String RESULT_COMPATIBLE = "compatible";
    private static final String RESULT_EXTENSION = "extension";
    private static final String RESULT_CONFLICT = "conflict";
    private static final String RESULT_UNKNOWN = "unknown";

    private final ChapterMapper chapterMapper;
    private final ChapterPlanVersionMapper planMapper;
    private final ScenePlanVersionMapper sceneMapper;
    private final ScenePlanConsistencyReportMapper reportMapper;
    private final AiTaskMapper taskMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final ChapterBriefMapper briefMapper;
    private final ChapterConsensusCodec consensusCodec;
    private final ChapterConsensusValidator consensusValidator;
    private final OutlineCandidateContentCodec outlineCodec;
    private final ObjectMapper objectMapper;
    private AgentRuntime agentRuntime;

    public ScenePlanConsistencyServiceImpl(ChapterMapper chapterMapper, ChapterPlanVersionMapper planMapper,
            ScenePlanVersionMapper sceneMapper, ScenePlanConsistencyReportMapper reportMapper, AiTaskMapper taskMapper,
            ChapterOutlineQueryMapper outlineMapper, ChapterBriefMapper briefMapper, ChapterConsensusCodec consensusCodec,
            ChapterConsensusValidator consensusValidator, OutlineCandidateContentCodec outlineCodec, ObjectMapper objectMapper) {
        this.chapterMapper = chapterMapper;
        this.planMapper = planMapper;
        this.sceneMapper = sceneMapper;
        this.reportMapper = reportMapper;
        this.taskMapper = taskMapper;
        this.outlineMapper = outlineMapper;
        this.briefMapper = briefMapper;
        this.consensusCodec = consensusCodec;
        this.consensusValidator = consensusValidator;
        this.outlineCodec = outlineCodec;
        this.objectMapper = objectMapper;
    }

    @Autowired
    public void setAgentRuntime(@Lazy AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ConsistencyReportView create(Long chapterId, Long planId, CheckRequest request) {
        ChapterEntity chapter = requireChapter(chapterId);
        ChapterPlanVersionEntity plan = requirePlan(chapterId, planId);
        if (request == null || request.baseVersion() == null || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "一致性检查必须提供 baseVersion 和 idempotencyKey");
        }
        if (!request.baseVersion().equals(plan.getVersion())) {
            throw conflict("场景规划版本已变化，请刷新后重新检查");
        }
        List<ScenePlanContent> scenes = scenes(planId);
        String snapshot = json(scenes);
        String fingerprint = hash(plan.getId() + ":" + plan.getVersion() + ":" + plan.getSourceSnapshotId() + ":" + snapshot);
        ScenePlanConsistencyReportEntity existing = reportMapper.selectOne(
                new LambdaQueryWrapper<ScenePlanConsistencyReportEntity>().eq(ScenePlanConsistencyReportEntity::getChapterId, chapterId)
                        .eq(ScenePlanConsistencyReportEntity::getIdempotencyKey, request.idempotencyKey())
                        .eq(ScenePlanConsistencyReportEntity::getDeleted, 0));
        if (existing != null) {
            if (!fingerprint.equals(existing.getInputFingerprint())) {
                throw new BusinessException(ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT, "幂等键已绑定不同场景规划输入");
            }
            return view(existing);
        }
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(WORKFLOW_TYPE);
        task.setTaskStatus(STATUS_QUEUED);
        task.setWorkId(chapter.getWorkId());
        task.setChapterId(chapterId);
        task.setTaskInputJson(json(Map.of("scenePlanId", planId, "planVersion", plan.getVersion(), "sourceSnapshotId",
                plan.getSourceSnapshotId(), "inputFingerprint", fingerprint)));
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);
        ScenePlanConsistencyReportEntity report = new ScenePlanConsistencyReportEntity();
        report.setWorkId(chapter.getWorkId());
        report.setChapterId(chapterId);
        report.setChapterPlanVersionId(planId);
        report.setPlanVersion(plan.getVersion());
        report.setSourceSnapshotId(plan.getSourceSnapshotId());
        report.setAiTaskId(task.getId());
        report.setIdempotencyKey(request.idempotencyKey());
        report.setInputFingerprint(fingerprint);
        report.setPlanSnapshotJson(snapshot);
        report.setReportStatus(STATUS_QUEUED);
        report.setRulesetVersion("scene-plan-rules-v1");
        report.setEvaluatorVersion("scene-plan-evaluator-v2");
        report.setDeleted(0);
        report.setVersion(0);
        reportMapper.insert(report);
        AgentRunView run = agentRuntime.start(new StartAgentRunCommand(LOCAL_USER, chapter.getWorkId(), chapterId,
                WORKFLOW_TYPE, request.idempotencyKey(), (long) plan.getVersion(), Map.of("reportId", report.getId()), task.getId()));
        report.setAgentRunId(run.runId());
        reportMapper.updateById(report);
        return view(report);
    }

    @Override
    public ConsistencyReportView latest(Long chapterId, Long planId) {
        requirePlan(chapterId, planId);
        ScenePlanConsistencyReportEntity report = reportMapper.selectOne(
                new LambdaQueryWrapper<ScenePlanConsistencyReportEntity>().eq(ScenePlanConsistencyReportEntity::getChapterId, chapterId)
                        .eq(ScenePlanConsistencyReportEntity::getChapterPlanVersionId, planId)
                        .eq(ScenePlanConsistencyReportEntity::getDeleted, 0).orderByDesc(ScenePlanConsistencyReportEntity::getId)
                        .last("LIMIT 1"));
        return report == null ? null : view(report);
    }

    @Override
    public ConsistencyReportView get(Long chapterId, Long reportId) {
        return view(requireReport(chapterId, reportId));
    }

    @Override
    public AgentRunView retry(Long chapterId, Long reportId, RetryRequest request) {
        ScenePlanConsistencyReportEntity report = requireReport(chapterId, reportId);
        if (request == null || request.expectedAttempt() == null || report.getAgentRunId() == null) {
            throw conflict("一致性报告当前不能重试");
        }
        return agentRuntime.retryStep(new RetryAgentStepCommand(report.getAgentRunId(), "semantic_evaluate", request.expectedAttempt()));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView cancel(Long chapterId, Long reportId) {
        ScenePlanConsistencyReportEntity report = requireReport(chapterId, reportId);
        if (report.getAgentRunId() == null) {
            throw conflict("一致性报告未关联运行任务");
        }
        AgentRunView run = agentRuntime.cancel(report.getAgentRunId());
        reportMapper.update(null, new UpdateWrapper<ScenePlanConsistencyReportEntity>().eq("id", reportId)
                .eq("version", report.getVersion()).in("report_status", STATUS_QUEUED, "running")
                .set("report_status", STATUS_CANCELED).setSql("version = version + 1"));
        return run;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public BriefView createDiscussionProposal(Long chapterId, Long reportId, DiscussionProposalRequest request) {
        ScenePlanConsistencyReportEntity report = requireReport(chapterId, reportId);
        if (!STATUS_READY.equals(report.getReportStatus()) || request == null || request.baseBriefId() == null
                || request.baseBriefVersion() == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw conflict("仅当前已完成的报告可以带回讨论");
        }
        Set<String> selected = new LinkedHashSet<>(request.issueKeys() == null ? List.of() : request.issueKeys());
        List<ConsistencyFinding> findings = findings(report).stream()
                .filter(item -> selected.contains(item.issueKey()))
                .filter(item -> Set.of(RESULT_EXTENSION, RESULT_CONFLICT, RESULT_UNKNOWN).contains(item.resultStatus())).toList();
        if (findings.isEmpty() || findings.size() != selected.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须选择报告中的可讨论问题");
        }
        ChapterBriefEntity base = briefMapper.findByIdAndChapterId(request.baseBriefId(), chapterId);
        ChapterBriefEntity latest = briefMapper.findLatestByChapterIdAndStatus(chapterId, "confirmed");
        if (base == null || latest == null || !base.getId().equals(latest.getId()) || !request.baseBriefVersion().equals(base.getVersion())) {
            throw new BusinessException(ErrorCode.CHAPTER_BRIEF_VERSION_CONFLICT, "已确认共识已变化，请刷新后重新创建讨论草稿");
        }
        ChapterBriefEntity existing = briefMapper.selectOne(new LambdaQueryWrapper<ChapterBriefEntity>()
                .eq(ChapterBriefEntity::getChapterId, chapterId).eq(ChapterBriefEntity::getTriggerSource, "scene_plan_feedback")
                .eq(ChapterBriefEntity::getIdempotencyKey, request.idempotencyKey()).eq(ChapterBriefEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (existing != null) {
            return briefView(existing);
        }
        ChapterConsensusDocument document = consensusCodec.read(base.getBriefContent());
        if (document.consensus() == null) {
            throw new BusinessException(ErrorCode.CHAPTER_CONSENSUS_INVALID, "当前已确认共识不是结构化版本");
        }
        List<Decision> decisions = new ArrayList<>(document.consensus().decisions());
        findings.forEach(item -> decisions.add(new Decision("scene-plan-change:" + reportId + ":" + item.issueKey(),
                "场景规划改动需要确认", "candidate", true, item.differenceSummary(), item.differenceSummary(), List.of(), List.of())));
        ChapterConsensusContentV1 content = new ChapterConsensusContentV1(document.consensus().schemaVersion(),
                document.consensus().chapterTask(), document.consensus().stateChange(), document.consensus().keyPush(),
                document.consensus().readerProgress(), document.consensus().writingBoundaries(), decisions,
                document.consensus().scopeCandidates());
        ChapterBriefEntity draft = new ChapterBriefEntity();
        draft.setWorkId(base.getWorkId());
        draft.setChapterId(chapterId);
        draft.setBriefStatus("draft");
        draft.setTriggerSource("scene_plan_feedback");
        draft.setBaseBriefId(base.getId());
        draft.setSourceAssetType("scene_plan");
        draft.setSourceAssetId(report.getChapterPlanVersionId());
        draft.setSourceReportId(reportId);
        draft.setIdempotencyKey(request.idempotencyKey());
        draft.setBriefContent(consensusCodec.write(consensusValidator.normalizeDraft(content)));
        draft.setDeleted(0);
        draft.setVersion(0);
        briefMapper.insert(draft);
        return briefView(draft);
    }

    @Override
    public void requirePublishable(Long chapterId, Long planId, Integer planVersion, Long reportId, Boolean acknowledgeUnknown) {
        ScenePlanConsistencyReportEntity report = requireReport(chapterId, reportId);
        if (!STATUS_READY.equals(report.getReportStatus()) || !planId.equals(report.getChapterPlanVersionId())
                || !planVersion.equals(report.getPlanVersion())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_CONSISTENCY_REQUIRED, "请先完成当前版本的一致性检查");
        }
        if (RESULT_CONFLICT.equals(report.getResultStatus())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_CONSISTENCY_CONFLICT, "场景规划存在明确冲突，不能发布");
        }
        if (RESULT_UNKNOWN.equals(report.getResultStatus()) && !Boolean.TRUE.equals(acknowledgeUnknown)) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_CONSISTENCY_REQUIRED, "请明确确认无法判断项，或将改动带回讨论");
        }
        if (RESULT_UNKNOWN.equals(report.getResultStatus())) {
            reportMapper.update(null, new UpdateWrapper<ScenePlanConsistencyReportEntity>().eq("id", report.getId())
                    .eq("version", report.getVersion()).set("resolution_status", "accepted_unknown")
                    .setSql("version = version + 1"));
        }
    }

    @Override
    public void requireGenerationAllowed(Long chapterId, Long planId) {
        ChapterPlanVersionEntity plan = requirePlan(chapterId, planId);
        if (!"current".equals(plan.getValidityStatus()) || plan.getPublishedConsistencyReportId() == null) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_CONSISTENCY_REQUIRED, "已发布场景规划尚未完成当前一致性检查");
        }
        ScenePlanConsistencyReportEntity report = requireReport(chapterId, plan.getPublishedConsistencyReportId());
        if (!STATUS_READY.equals(report.getReportStatus()) || RESULT_CONFLICT.equals(report.getResultStatus())
                || RESULT_UNKNOWN.equals(report.getResultStatus()) && !"accepted_unknown".equals(report.getResolutionStatus())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_CONSISTENCY_REQUIRED, "场景规划一致性状态不允许生成正文");
        }
    }

    /** 由工作流写入确定性规则结果；计划或来源变化时明确标记过期。 */
    @Transactional(rollbackFor = RuntimeException.class)
    public List<ConsistencyFinding> evaluateRules(Long reportId) {
        ScenePlanConsistencyReportEntity report = requireReportById(reportId);
        ChapterPlanVersionEntity plan = planMapper.selectById(report.getChapterPlanVersionId());
        if (plan == null || !report.getPlanVersion().equals(plan.getVersion())
                || !java.util.Objects.equals(report.getSourceSnapshotId(), plan.getSourceSnapshotId())) {
            markStatus(report, STATUS_STALE, null, List.of());
            return List.of();
        }
        ChapterOutlineEntity outline = outlineMapper.findLatest(report.getChapterId());
        if (outline == null || !plan.getOutlineId().equals(outline.getId()) || !plan.getOutlineRevision().equals(outline.getRevision())) {
            markStatus(report, STATUS_STALE, null, List.of());
            return List.of();
        }
        OutlineCandidateContent outlineContent = outlineCodec.read(outline.getOutlineContent());
        List<ScenePlanContent> scenes = readScenes(report.getPlanSnapshotJson());
        List<ConsistencyFinding> result = new ArrayList<>();
        Map<String, Integer> beatOrder = new LinkedHashMap<>();
        for (int index = 0; index < outlineContent.beats().size(); index++) {
            beatOrder.put(outlineContent.beats().get(index).beatKey(), index);
        }
        Map<String, Integer> firstSequence = new LinkedHashMap<>();
        for (ScenePlanContent scene : scenes) {
            for (String beat : scene.outlineBeatKeys()) {
                if (!beatOrder.containsKey(beat)) {
                    result.add(finding("unknown-beat:" + scene.sceneKey() + ":" + beat, RESULT_CONFLICT, "blocking", scene,
                            "outlineBeatKeys", "场景引用了不存在的章纲节拍", "修改当前场景"));
                } else if ("planned".equals(scene.status())) {
                    firstSequence.merge(beat, scene.sequence(), Math::min);
                }
            }
        }
        for (String beat : beatOrder.keySet()) {
            if (!firstSequence.containsKey(beat)) {
                result.add(new ConsistencyFinding("missing-beat:" + beat, RESULT_CONFLICT, "blocking", 1D, "rule", List.of(),
                        List.of("outlineBeatKeys"), List.of(new FindingSourceRef("outline_beat", beat, String.valueOf(plan.getOutlineRevision()),
                                "beats", "未被参与生成的场景覆盖")), "关键剧情节拍未被场景覆盖", "修改当前场景"));
            }
        }
        int previous = -1;
        for (String beat : beatOrder.keySet()) {
            Integer sequence = firstSequence.get(beat);
            if (sequence != null && sequence < previous) {
                result.add(new ConsistencyFinding("beat-order:" + beat, RESULT_CONFLICT, "blocking", 1D, "rule", List.of(),
                        List.of("sequence"), List.of(), "场景顺序与章纲节拍顺序倒置", "修改当前场景"));
            }
            if (sequence != null) {
                previous = sequence;
            }
        }
        if (scenes.stream().anyMatch(scene -> scene.outlineBeatKeys().isEmpty())) {
            result.add(new ConsistencyFinding("legacy-or-transition-beat-mapping", RESULT_UNKNOWN, "warning", 0.5D, "rule",
                    scenes.stream().filter(scene -> scene.outlineBeatKeys().isEmpty()).map(ScenePlanContent::sceneKey).toList(),
                    List.of("outlineBeatKeys"), List.of(), "存在未关联章纲节拍的场景，无法可靠判断其叙事影响", "带回讨论"));
        }
        return List.copyOf(result);
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void markRunning(Long reportId) {
        ScenePlanConsistencyReportEntity report = requireReportById(reportId);
        if (STATUS_QUEUED.equals(report.getReportStatus())) {
            markStatus(report, "running", null, findings(report));
        }
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void completeReport(Long reportId, List<ConsistencyFinding> findings) {
        ScenePlanConsistencyReportEntity report = requireReportById(reportId);
        if (STATUS_STALE.equals(report.getReportStatus()) || STATUS_CANCELED.equals(report.getReportStatus())) {
            return;
        }
        markStatus(report, STATUS_READY, aggregate(findings), findings);
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void failReport(Long reportId) {
        ScenePlanConsistencyReportEntity report = requireReportById(reportId);
        if (!STATUS_STALE.equals(report.getReportStatus()) && !STATUS_CANCELED.equals(report.getReportStatus())) {
            markStatus(report, "failed", null, List.of());
        }
    }

    private void markStatus(ScenePlanConsistencyReportEntity report, String status, String result,
            List<ConsistencyFinding> findings) {
        reportMapper.update(null, new UpdateWrapper<ScenePlanConsistencyReportEntity>().eq("id", report.getId())
                .eq("version", report.getVersion()).set("report_status", status).set("result_status", result)
                .set("findings_json", json(findings)).setSql("version = version + 1"));
    }

    private String aggregate(List<ConsistencyFinding> findings) {
        Set<String> statuses = findings.stream().map(ConsistencyFinding::resultStatus).collect(java.util.stream.Collectors.toSet());
        if (statuses.contains(RESULT_CONFLICT)) {
            return RESULT_CONFLICT;
        }
        if (statuses.contains(RESULT_UNKNOWN)) {
            return RESULT_UNKNOWN;
        }
        if (statuses.contains(RESULT_EXTENSION)) {
            return RESULT_EXTENSION;
        }
        return RESULT_COMPATIBLE;
    }

    private ConsistencyFinding finding(String key, String result, String severity, ScenePlanContent scene, String field,
            String summary, String action) {
        return new ConsistencyFinding(key, result, severity, 1D, "rule", List.of(scene.sceneKey()), List.of(field), List.of(), summary,
                action);
    }

    private List<ScenePlanContent> scenes(Long planId) {
        return sceneMapper.selectList(new LambdaQueryWrapper<ScenePlanVersionEntity>()
                .eq(ScenePlanVersionEntity::getChapterPlanVersionId, planId).eq(ScenePlanVersionEntity::getDeleted, 0)
                .orderByAsc(ScenePlanVersionEntity::getSequenceNo)).stream()
                .map(item -> read(item.getContentJson(), ScenePlanContent.class)).toList();
    }

    private List<ScenePlanContent> readScenes(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_INVALID, "场景规划检查快照无法读取", exception);
        }
    }

    private List<ConsistencyFinding> findings(ScenePlanConsistencyReportEntity report) {
        if (report.getFindingsJson() == null || report.getFindingsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(report.getFindingsJson(), new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "一致性报告结果无法读取", exception);
        }
    }

    private ConsistencyReportView view(ScenePlanConsistencyReportEntity report) {
        return new ConsistencyReportView(report.getId(), report.getChapterId(), report.getChapterPlanVersionId(),
                report.getPlanVersion(), report.getSourceSnapshotId(), report.getAiTaskId(), report.getAgentRunId(),
                report.getReportStatus(), report.getResultStatus(), findings(report), report.getResolutionStatus(),
                report.getRulesetVersion(), report.getEvaluatorVersion(), report.getVersion(), report.getGmtCreate(),
                report.getGmtModified());
    }

    private BriefView briefView(ChapterBriefEntity brief) {
        ChapterConsensusDocument document = consensusCodec.read(brief.getBriefContent());
        return new BriefView(brief.getId(), brief.getWorkId(), brief.getChapterId(), brief.getBriefStatus(), brief.getVersion(),
                document.contentFormat(), document.consensus(), document.legacyText(), brief.getTriggerSource(), brief.getBaseBriefId(),
                brief.getSourceAssetType(), brief.getSourceAssetId(), brief.getSourceReportId(), brief.getGmtCreate(), brief.getGmtModified());
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private ChapterPlanVersionEntity requirePlan(Long chapterId, Long planId) {
        ChapterPlanVersionEntity plan = planId == null ? null : planMapper.selectById(planId);
        if (plan == null || Integer.valueOf(1).equals(plan.getDeleted()) || !chapterId.equals(plan.getChapterId())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_NOT_FOUND, "场景规划不存在");
        }
        return plan;
    }

    private ScenePlanConsistencyReportEntity requireReport(Long chapterId, Long reportId) {
        ScenePlanConsistencyReportEntity report = requireReportById(reportId);
        if (!chapterId.equals(report.getChapterId())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_NOT_FOUND, "一致性报告不属于当前章节");
        }
        return report;
    }

    private ScenePlanConsistencyReportEntity requireReportById(Long reportId) {
        ScenePlanConsistencyReportEntity report = reportId == null ? null : reportMapper.selectById(reportId);
        if (report == null || Integer.valueOf(1).equals(report.getDeleted())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_NOT_FOUND, "一致性报告不存在");
        }
        return report;
    }

    private <T> T read(String value, Class<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (Exception exception) { throw new BusinessException(ErrorCode.SCENE_PLAN_INVALID, "场景规划内容无法读取", exception); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "一致性检查数据无法序列化", exception); }
    }

    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法计算一致性输入指纹", exception); }
    }

    private BusinessException conflict(String message) { return new BusinessException(ErrorCode.SCENE_PLAN_CONSISTENCY_CONFLICT, message); }
}
