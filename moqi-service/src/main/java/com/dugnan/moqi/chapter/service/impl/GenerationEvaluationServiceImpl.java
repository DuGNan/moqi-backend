package com.dugnan.moqi.chapter.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.CreateEvaluationRequest;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationFinding;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationReportView;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.RetryEvaluationRequest;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.RevisionCandidateView;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationRevisionCandidateEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationRevisionCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.context.entity.StoryContextSnapshotEntity;
import com.dugnan.moqi.context.mapper.StoryContextSnapshotMapper;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 实现正文候选评价的冻结输入、幂等运行和仅候选式局部修订。
 */
@Service
public class GenerationEvaluationServiceImpl implements GenerationEvaluationService {

    public static final String WORKFLOW_TYPE = "chapter_generation_evaluation_v1";
    private static final String LOCAL_USER = "local-user";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_CANCELED = "canceled";
    private static final String STATUS_STALE = "stale";

    private final ChapterGenerationMapper generationMapper;
    private final ChapterGenerationSceneMapper sceneMapper;
    private final ChapterGenerationEvaluationReportMapper reportMapper;
    private final ChapterGenerationRevisionCandidateMapper revisionMapper;
    private final AiTaskMapper taskMapper;
    private final AgentRuntime agentRuntime;
    private final ObjectMapper objectMapper;
    private final StoryContextSnapshotMapper contextSnapshotMapper;
    private final ScenePlanVersionMapper scenePlanVersionMapper;

    public GenerationEvaluationServiceImpl(ChapterGenerationMapper generationMapper,
            ChapterGenerationSceneMapper sceneMapper, ChapterGenerationEvaluationReportMapper reportMapper,
            ChapterGenerationRevisionCandidateMapper revisionMapper, AiTaskMapper taskMapper,
            AgentRuntime agentRuntime, ObjectMapper objectMapper, StoryContextSnapshotMapper contextSnapshotMapper,
            ScenePlanVersionMapper scenePlanVersionMapper) {
        this.generationMapper = generationMapper;
        this.sceneMapper = sceneMapper;
        this.reportMapper = reportMapper;
        this.revisionMapper = revisionMapper;
        this.taskMapper = taskMapper;
        this.agentRuntime = agentRuntime;
        this.objectMapper = objectMapper;
        this.contextSnapshotMapper = contextSnapshotMapper;
        this.scenePlanVersionMapper = scenePlanVersionMapper;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public EvaluationReportView create(Long chapterId, Long generationId, CreateEvaluationRequest request) {
        ChapterGenerationEntity generation = requireGeneration(chapterId, generationId);
        if (request == null || blank(request.idempotencyKey())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评价请求必须提供 idempotencyKey");
        }
        ChapterGenerationSceneEntity scene = requireScene(generationId, request.generationSceneId());
        String source = snapshot(generation, scene);
        String fingerprint = hash(source);
        ChapterGenerationEvaluationReportEntity existing = reportMapper.selectOne(
                new LambdaQueryWrapper<ChapterGenerationEvaluationReportEntity>()
                        .eq(ChapterGenerationEvaluationReportEntity::getGenerationId, generationId)
                        .eq(ChapterGenerationEvaluationReportEntity::getIdempotencyKey, request.idempotencyKey())
                        .eq(ChapterGenerationEvaluationReportEntity::getDeleted, 0));
        if (existing != null) {
            if (!fingerprint.equals(existing.getInputFingerprint())) {
                throw new BusinessException(ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT, "幂等键已绑定不同的正文候选输入");
            }
            return view(existing);
        }
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(WORKFLOW_TYPE);
        task.setTaskStatus(STATUS_QUEUED);
        task.setWorkId(generation.getWorkId());
        task.setChapterId(chapterId);
        task.setTaskInputJson(json(Map.of("generationId", generationId, "inputFingerprint", fingerprint)));
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);
        ChapterGenerationEvaluationReportEntity report = new ChapterGenerationEvaluationReportEntity();
        report.setWorkId(generation.getWorkId());
        report.setChapterId(chapterId);
        report.setGenerationId(generationId);
        report.setGenerationSceneId(scene == null ? null : scene.getId());
        report.setContextSnapshotId(scene == null ? generation.getSourceSnapshotId() : scene.getContextSnapshotId());
        report.setAiTaskId(task.getId());
        report.setIdempotencyKey(request.idempotencyKey());
        report.setInputFingerprint(fingerprint);
        report.setSourceSnapshotJson(source);
        report.setReportStatus(STATUS_QUEUED);
        report.setRulesetVersion("generation-rules-v1");
        report.setEvaluatorVersion("generation-evaluator-v1");
        report.setRevisionAttempt(0);
        report.setDeleted(0);
        report.setVersion(0);
        reportMapper.insert(report);
        AgentRunView run = agentRuntime.start(new StartAgentRunCommand(LOCAL_USER, generation.getWorkId(), chapterId,
                WORKFLOW_TYPE, request.idempotencyKey(), generation.getVersion().longValue(),
                Map.of("reportId", report.getId()), task.getId()));
        reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>().eq("id", report.getId())
                .eq("version", report.getVersion()).set("agent_run_id", run.runId()).setSql("version = version + 1"));
        return get(chapterId, generationId, report.getId());
    }

    @Override
    public EvaluationReportView latest(Long chapterId, Long generationId, Long generationSceneId) {
        requireGeneration(chapterId, generationId);
        ChapterGenerationEvaluationReportEntity report = reportMapper.selectOne(
                new LambdaQueryWrapper<ChapterGenerationEvaluationReportEntity>()
                        .eq(ChapterGenerationEvaluationReportEntity::getGenerationId, generationId)
                        .eq(generationSceneId != null, ChapterGenerationEvaluationReportEntity::getGenerationSceneId, generationSceneId)
                        .eq(ChapterGenerationEvaluationReportEntity::getDeleted, 0).orderByDesc(ChapterGenerationEvaluationReportEntity::getId)
                        .last("LIMIT 1"));
        return report == null ? null : view(report);
    }

    @Override
    public EvaluationReportView get(Long chapterId, Long generationId, Long reportId) {
        return view(requireReport(chapterId, generationId, reportId));
    }

    @Override
    public AgentRunView retry(Long chapterId, Long generationId, Long reportId, RetryEvaluationRequest request) {
        ChapterGenerationEvaluationReportEntity report = requireReport(chapterId, generationId, reportId);
        if (report.getAgentRunId() == null || request == null || request.expectedAttempt() == null) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "当前评价报告不能重试");
        }
        return agentRuntime.retryStep(new RetryAgentStepCommand(report.getAgentRunId(), "semantic_evaluate", request.expectedAttempt()));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView cancel(Long chapterId, Long generationId, Long reportId) {
        ChapterGenerationEvaluationReportEntity report = requireReport(chapterId, generationId, reportId);
        if (report.getAgentRunId() == null) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "评价报告未关联运行任务");
        }
        AgentRunView run = agentRuntime.cancel(report.getAgentRunId());
        reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>().eq("id", reportId)
                .in("report_status", STATUS_QUEUED, "running").set("report_status", STATUS_CANCELED)
                .setSql("version = version + 1"));
        return run;
    }

    @Override
    public RevisionCandidateView revisionCandidate(Long chapterId, Long generationId, Long reportId) {
        requireReport(chapterId, generationId, reportId);
        ChapterGenerationRevisionCandidateEntity candidate = revisionMapper.selectOne(
                new LambdaQueryWrapper<ChapterGenerationRevisionCandidateEntity>().eq(ChapterGenerationRevisionCandidateEntity::getReportId, reportId)
                        .eq(ChapterGenerationRevisionCandidateEntity::getDeleted, 0).last("LIMIT 1"));
        return candidate == null ? null : revisionView(candidate);
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void markRunning(Long reportId) {
        reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>().eq("id", reportId)
                .eq("report_status", STATUS_QUEUED).set("report_status", "running").setSql("version = version + 1"));
    }

    public List<EvaluationFinding> deterministicFindings(Long reportId) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        ensureCurrent(report);
        ChapterGenerationSceneEntity scene = report.getGenerationSceneId() == null ? null : sceneMapper.selectById(report.getGenerationSceneId());
        if (scene != null && blank(scene.getGeneratedContent())) {
            return List.of(new EvaluationFinding("empty-scene", "scene_content", "blocking", 1D, "rule", scene.getId(),
                    "全文", null, "场景候选正文为空，不能进入采纳流程", "重新生成该场景"));
        }
        if (scene == null) {
            return List.of(new EvaluationFinding("batch-only-evaluation", "scope", "info", 1D, "rule", null,
                    null, null, "批次级评价缺少单场景目标和引用范围，结构规则仅能在场景级执行", "选择具体场景后检查"));
        }
        ScenePlanVersionEntity plan = scene.getScenePlanVersionId() == null ? null : scenePlanVersionMapper.selectById(scene.getScenePlanVersionId());
        if (plan == null || Integer.valueOf(1).equals(plan.getDeleted())) {
            return List.of(new EvaluationFinding("scene-plan-missing", "scene_plan", "blocking", 1D, "rule", scene.getId(),
                    "场景规划引用", null, "场景候选未绑定可读取的场景规划版本", "重新生成并绑定当前场景规划"));
        }
        ScenePlanContent content = readPlan(plan.getContentJson());
        if (!scene.getSceneKey().equals(content.sceneKey()) || !scene.getSequenceNo().equals(content.sequence())) {
            return List.of(new EvaluationFinding("scene-plan-order-mismatch", "scene_order", "blocking", 1D, "rule", scene.getId(),
                    "sceneKey/sequence", null, "生成场景与冻结场景规划的键或顺序不一致", "重新生成该场景"));
        }
        ChapterGenerationSceneEntity previous = previousScene(report.getGenerationId(), scene.getSequenceNo());
        if (previous != null && blank(previous.getGeneratedContent())) {
            return List.of(new EvaluationFinding("adjacent-scene-missing", "scene_transition", "warning", 1D, "rule", scene.getId(),
                    "相邻场景", null, "前一场景候选正文缺失，无法可靠判断场景衔接", "先生成或恢复前一场景"));
        }
        return List.of(new EvaluationFinding("scene-plan-reference-semantic", "scene_plan_reference", "info", 1D, "rule", scene.getId(),
                "目标/结果/角色/地点/设定/伏笔", null, "场景规划引用和目标结果需要基于冻结正文进行语义评价，未以字符串包含关系伪造通过", "查看语义评价结果"));
    }

    /** 返回仅供模型评价使用的已固化来源快照。 */
    public String semanticSource(Long reportId) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        ensureCurrent(report);
        return report.getSourceSnapshotJson();
    }

    /** 返回报告输入的稳定指纹，用于模型调用观测来源关联。 */
    public String sourceFingerprint(Long reportId) {
        return requireReportById(reportId).getInputFingerprint();
    }

    /** 校验模型 Finding 只能引用当前批次中的已固化场景。 */
    public List<EvaluationFinding> validateSemanticFindings(Long reportId, List<EvaluationFinding> findings) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        ensureCurrent(report);
        if (findings == null || findings.size() > 30) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型评价 Finding 数量非法");
        }
        for (EvaluationFinding finding : findings) {
            if (finding == null || blank(finding.issueKey()) || blank(finding.category()) || blank(finding.severity())
                    || finding.confidence() == null || finding.confidence() < 0D || finding.confidence() > 1D
                    || !List.of("blocking", "warning", "info").contains(finding.severity())
                    || finding.generationSceneId() != null && !belongsToGeneration(report.getGenerationId(), finding.generationSceneId())
                    || finding.storyFactRef() != null && !allowedStoryFactRefs(report).contains(finding.storyFactRef())
                    || finding.summary() == null || finding.summary().length() > 500
                    || finding.evidenceRange() != null && finding.evidenceRange().length() > 200) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "模型评价 Finding 不符合安全契约");
            }
        }
        return List.copyOf(findings);
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void complete(Long reportId, List<EvaluationFinding> findings) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        if (STATUS_STALE.equals(report.getReportStatus()) || STATUS_CANCELED.equals(report.getReportStatus())) {
            return;
        }
        if (!isCurrent(report)) {
            reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>().eq("id", reportId)
                    .set("report_status", STATUS_STALE).setSql("version = version + 1"));
            return;
        }
        String conclusion = findings.stream().anyMatch(item -> "blocking".equals(item.severity())) ? "conflict" : "compatible";
        reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>().eq("id", reportId)
                .set("report_status", STATUS_READY).set("conclusion", conclusion).set("findings_json", json(findings))
                .setSql("version = version + 1"));
        if (report.getRevisionCandidateId() != null) {
            revisionMapper.update(null, new UpdateWrapper<ChapterGenerationRevisionCandidateEntity>()
                    .eq("id", report.getRevisionCandidateId()).set("candidate_status",
                            "compatible".equals(conclusion) ? "passed" : "needs_user_action")
                    .setSql("version = version + 1"));
        }
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void fail(Long reportId) {
        reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>().eq("id", reportId)
                .notIn("report_status", STATUS_STALE, STATUS_CANCELED).set("report_status", "failed")
                .setSql("version = version + 1"));
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        if (report.getRevisionCandidateId() != null) {
            revisionMapper.update(null, new UpdateWrapper<ChapterGenerationRevisionCandidateEntity>()
                    .eq("id", report.getRevisionCandidateId()).set("candidate_status", "failed").setSql("version = version + 1"));
        }
    }

    /** 判断本报告是否还能启动唯一的一次局部修订。 */
    public boolean shouldRevise(Long reportId, List<EvaluationFinding> findings) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        return (report.getRevisionAttempt() == null || report.getRevisionAttempt() == 0)
                && eligibleFinding(findings) != null && isCurrent(report);
    }

    /** 构造仅包含冻结来源、证据范围和原场景正文的修订输入。 */
    public Map<String, Object> revisionInput(Long reportId, List<EvaluationFinding> findings) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        ensureCurrent(report);
        EvaluationFinding finding = eligibleFinding(findings);
        if (finding == null) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "当前报告不满足局部修订条件");
        }
        ChapterGenerationSceneEntity scene = sceneMapper.selectById(finding.generationSceneId());
        return Map.of("sourceSnapshot", report.getSourceSnapshotJson(), "evidenceRange", finding.evidenceRange(),
                "findingSummary", finding.summary(), "originalSceneContent", scene.getGeneratedContent());
    }

    /** 持久化一次修订正文候选，绝不回写原场景或章节正文。 */
    @Transactional(rollbackFor = RuntimeException.class)
    public Long persistRevision(Long reportId, List<EvaluationFinding> findings, String revisionContent) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        ensureCurrent(report);
        if (report.getRevisionAttempt() != null && report.getRevisionAttempt() >= 1) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "每份评价报告最多自动修订一次");
        }
        EvaluationFinding finding = eligibleFinding(findings);
        if (finding == null || blank(revisionContent) || revisionContent.length() > 20000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "局部修订结果不符合结构化契约");
        }
        ChapterGenerationRevisionCandidateEntity candidate = new ChapterGenerationRevisionCandidateEntity();
        candidate.setReportId(reportId);
        candidate.setGenerationId(report.getGenerationId());
        candidate.setGenerationSceneId(finding.generationSceneId());
        candidate.setSourceSceneId(finding.generationSceneId());
        candidate.setCandidateStatus("re_evaluating");
        candidate.setRevisionContent(revisionContent.trim());
        candidate.setEvidenceRangeJson(json(List.of(finding.evidenceRange())));
        candidate.setSourceFingerprint(report.getInputFingerprint());
        candidate.setDeleted(0);
        candidate.setVersion(0);
        revisionMapper.insert(candidate);
        reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>().eq("id", reportId)
                .eq("version", report.getVersion()).set("revision_attempt", 1).set("revision_candidate_id", candidate.getId())
                .setSql("version = version + 1"));
        return candidate.getId();
    }

    /** 读取修订候选后的评价来源。 */
    public String revisedSemanticSource(Long reportId) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        ensureCurrent(report);
        if (report.getRevisionCandidateId() == null) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "修订候选不存在");
        }
        ChapterGenerationRevisionCandidateEntity candidate = revisionMapper.selectById(report.getRevisionCandidateId());
        if (candidate == null || blank(candidate.getRevisionContent())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "修订候选正文不存在");
        }
        return json(Map.of("sourceSnapshot", report.getSourceSnapshotJson(), "revisionContent", candidate.getRevisionContent(),
                "evidenceRanges", read(candidate.getEvidenceRangeJson())));
    }

    private String snapshot(ChapterGenerationEntity generation, ChapterGenerationSceneEntity scene) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("generationId", generation.getId());
        source.put("generationVersion", generation.getVersion());
        source.put("generationContentHash", hash(generation.getGeneratedContent() == null ? "" : generation.getGeneratedContent()));
        source.put("generationContent", generation.getGeneratedContent());
        source.put("sceneId", scene == null ? null : scene.getId());
        source.put("sceneContentHash", scene == null ? null : scene.getContentHash());
        source.put("sceneContent", scene == null ? null : scene.getGeneratedContent());
        source.put("contextSnapshotId", scene == null ? generation.getSourceSnapshotId() : scene.getContextSnapshotId());
        Long contextSnapshotId = scene == null ? generation.getSourceSnapshotId() : scene.getContextSnapshotId();
        StoryContextSnapshotEntity contextSnapshot = contextSnapshotId == null ? null : contextSnapshotMapper.selectById(contextSnapshotId);
        source.put("contextSnapshotHash", contextSnapshot == null ? null : contextSnapshot.getContentHash());
        source.put("contextSnapshot", contextSnapshot == null ? null : contextSnapshot.getSnapshotJson());
        return json(source);
    }

    private void ensureCurrent(ChapterGenerationEvaluationReportEntity report) {
        if (!isCurrent(report)) {
            reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>().eq("id", report.getId())
                    .set("report_status", STATUS_STALE).setSql("version = version + 1"));
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "正文候选或上下文来源已变化，评价报告已过期");
        }
    }

    private boolean isCurrent(ChapterGenerationEvaluationReportEntity report) {
        ChapterGenerationEntity generation = generationMapper.selectById(report.getGenerationId());
        if (generation == null || Integer.valueOf(1).equals(generation.getDeleted())) {
            return false;
        }
        ChapterGenerationSceneEntity scene = report.getGenerationSceneId() == null ? null : sceneMapper.selectById(report.getGenerationSceneId());
        if (report.getGenerationSceneId() != null && (scene == null || Integer.valueOf(1).equals(scene.getDeleted()))) {
            return false;
        }
        return hash(snapshot(generation, scene)).equals(report.getInputFingerprint());
    }

    private boolean belongsToGeneration(Long generationId, Long sceneId) {
        ChapterGenerationSceneEntity scene = sceneMapper.selectById(sceneId);
        return scene != null && Integer.valueOf(0).equals(scene.getDeleted()) && generationId.equals(scene.getGenerationId());
    }

    private ChapterGenerationSceneEntity previousScene(Long generationId, Integer sequenceNo) {
        if (sequenceNo == null || sequenceNo <= 1) {
            return null;
        }
        return sceneMapper.selectOne(new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                .eq(ChapterGenerationSceneEntity::getGenerationId, generationId)
                .eq(ChapterGenerationSceneEntity::getSequenceNo, sequenceNo - 1)
                .eq(ChapterGenerationSceneEntity::getDeleted, 0).last("LIMIT 1"));
    }

    private ScenePlanContent readPlan(String value) {
        try {
            return objectMapper.readValue(value, ScenePlanContent.class);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_INVALID, "冻结场景规划无法读取", exception);
        }
    }

    private java.util.Set<String> allowedStoryFactRefs(ChapterGenerationEvaluationReportEntity report) {
        try {
            JsonNode root = objectMapper.readTree(report.getSourceSnapshotJson());
            JsonNode snapshot = root.get("contextSnapshot");
            if (snapshot == null || snapshot.isNull()) {
                return java.util.Set.of();
            }
            JsonNode content = snapshot.isTextual() ? objectMapper.readTree(snapshot.textValue()) : snapshot;
            java.util.Set<String> result = new LinkedHashSet<>();
            collectSourceIds(content, result);
            return java.util.Set.copyOf(result);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "冻结上下文快照无法读取", exception);
        }
    }

    private void collectSourceIds(JsonNode node, java.util.Set<String> result) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode sourceId = node.get("sourceId");
            if (sourceId != null && sourceId.isValueNode()) {
                result.add(sourceId.asText());
            }
            node.elements().forEachRemaining(value -> collectSourceIds(value, result));
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(value -> collectSourceIds(value, result));
        }
    }

    private EvaluationFinding eligibleFinding(List<EvaluationFinding> findings) {
        EvaluationFinding finding = findings.stream().filter(item -> item.confidence() != null && item.confidence() >= 0.85D)
                .filter(item -> !blank(item.evidenceRange())).filter(item -> item.generationSceneId() != null)
                .filter(item -> !"blocking".equals(item.severity())).findFirst().orElse(null);
        return finding;
    }

    private EvaluationReportView view(ChapterGenerationEvaluationReportEntity report) {
        return new EvaluationReportView(report.getId(), report.getGenerationId(), report.getGenerationSceneId(), report.getContextSnapshotId(),
                report.getAiTaskId(), report.getAgentRunId(), report.getReportStatus(), report.getConclusion(), findings(report),
                report.getRulesetVersion(), report.getEvaluatorVersion(), report.getRevisionAttempt(),
                report.getRevisionCandidateId() == null ? null : revisionCandidate(report.getChapterId(), report.getGenerationId(), report.getId()),
                report.getVersion(), report.getGmtCreate(), report.getGmtModified());
    }

    private RevisionCandidateView revisionView(ChapterGenerationRevisionCandidateEntity item) {
        return new RevisionCandidateView(item.getId(), item.getReportId(), item.getGenerationId(), item.getGenerationSceneId(),
                item.getCandidateStatus(), item.getRevisionContent(), read(item.getEvidenceRangeJson()), item.getGmtCreate());
    }

    private List<EvaluationFinding> findings(ChapterGenerationEvaluationReportEntity report) {
        if (blank(report.getFindingsJson())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(report.getFindingsJson(), new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "评价报告结果无法读取", exception);
        }
    }

    private List<String> read(String value) {
        if (blank(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "修订候选证据无法读取", exception);
        }
    }

    private ChapterGenerationEntity requireGeneration(Long chapterId, Long generationId) {
        ChapterGenerationEntity generation = generationId == null ? null : generationMapper.selectById(generationId);
        if (generation == null || Integer.valueOf(1).equals(generation.getDeleted()) || !chapterId.equals(generation.getChapterId())) {
            throw new BusinessException(ErrorCode.GENERATION_NOT_FOUND, "正文生成批次不存在");
        }
        return generation;
    }

    private ChapterGenerationSceneEntity requireScene(Long generationId, Long sceneId) {
        if (sceneId == null) {
            return null;
        }
        ChapterGenerationSceneEntity scene = sceneMapper.selectById(sceneId);
        if (scene == null || Integer.valueOf(1).equals(scene.getDeleted()) || !generationId.equals(scene.getGenerationId())) {
            throw new BusinessException(ErrorCode.GENERATION_SCENE_NOT_FOUND, "正文场景候选不存在");
        }
        return scene;
    }

    private ChapterGenerationEvaluationReportEntity requireReport(Long chapterId, Long generationId, Long reportId) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        if (!chapterId.equals(report.getChapterId()) || !generationId.equals(report.getGenerationId())) {
            throw new BusinessException(ErrorCode.GENERATION_NOT_FOUND, "评价报告不属于当前正文批次");
        }
        return report;
    }

    private ChapterGenerationEvaluationReportEntity requireReportById(Long reportId) {
        ChapterGenerationEvaluationReportEntity report = reportId == null ? null : reportMapper.selectById(reportId);
        if (report == null || Integer.valueOf(1).equals(report.getDeleted())) {
            throw new BusinessException(ErrorCode.GENERATION_NOT_FOUND, "评价报告不存在");
        }
        return report;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "评价数据无法序列化", exception);
        }
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算评价输入指纹", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
