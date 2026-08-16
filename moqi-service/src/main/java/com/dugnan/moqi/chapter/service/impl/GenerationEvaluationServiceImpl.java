package com.dugnan.moqi.chapter.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
import com.dugnan.moqi.chapter.entity.BoundedChapterRevisionEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationRevisionCandidateEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.BoundedChapterRevisionMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationRevisionCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;
import com.dugnan.moqi.chapter.service.EvaluationFindingContractException;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.chapter.service.GenerationRetryMetadataResolver;
import com.dugnan.moqi.chapter.service.GenerationRetryMetadataResolver.RetryMetadata;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.context.entity.StoryContextSnapshotEntity;
import com.dugnan.moqi.context.mapper.StoryContextSnapshotMapper;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.sourcechain.entity.ChapterAssetSourceSnapshotEntity;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 实现正文候选评价的冻结输入、幂等运行和仅候选式局部修订。
 */
@Service
public class GenerationEvaluationServiceImpl implements GenerationEvaluationService {

    public static final String WORKFLOW_TYPE = "chapter_generation_evaluation_v1";
    public static final String RULESET_VERSION = "whole-chapter-rules-v2";
    public static final String EVALUATOR_VERSION = "whole-chapter-evaluator-v3";
    private static final String LOCAL_USER = "local-user";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_CANCELED = "canceled";
    private static final String STATUS_STALE = "stale";
    private static final String STATUS_FAILED = "failed";
    private static final String SEMANTIC_EVALUATE = "semantic_evaluate";
    private static final String GENERATION_PREVIEW = "preview";
    private static final String CONCLUSION_NEEDS_REVISION = "needs_revision";
    private static final String CONCLUSION_NEEDS_HUMAN = "needs_human";
    private static final String ASSEMBLY_BOUNDED_REVISION = "bounded_revision";
    private static final String BOUNDED_CANDIDATE_READY = "candidate_ready";
    private static final String BOUNDED_RE_EVALUATING = "re_evaluating";
    private static final String SEVERITY_BLOCKING = "blocking";
    private static final List<String> FINDING_SEVERITIES = List.of(SEVERITY_BLOCKING, "warning", "info");
    private static final int MAX_FINDING_COUNT = 30;
    private static final double MIN_CONFIDENCE = 0D;
    private static final double MAX_CONFIDENCE = 1D;

    private final ChapterGenerationMapper generationMapper;
    private final ChapterGenerationSceneMapper sceneMapper;
    private final ChapterGenerationEvaluationReportMapper reportMapper;
    private final ChapterGenerationRevisionCandidateMapper revisionMapper;
    private final BoundedChapterRevisionMapper boundedRevisionMapper;
    private final AiTaskMapper taskMapper;
    private AgentRuntime agentRuntime;
    private final ObjectMapper objectMapper;
    private final StoryContextSnapshotMapper contextSnapshotMapper;
    private final ChapterAssetSourceSnapshotMapper assetSourceSnapshotMapper;
    private final ScenePlanVersionMapper scenePlanVersionMapper;
    private final GenerationRetryMetadataResolver retryMetadataResolver;

    public GenerationEvaluationServiceImpl(ChapterGenerationMapper generationMapper,
            ChapterGenerationSceneMapper sceneMapper, ChapterGenerationEvaluationReportMapper reportMapper,
            ChapterGenerationRevisionCandidateMapper revisionMapper,
            BoundedChapterRevisionMapper boundedRevisionMapper, AiTaskMapper taskMapper,
            ObjectMapper objectMapper, StoryContextSnapshotMapper contextSnapshotMapper,
            ChapterAssetSourceSnapshotMapper assetSourceSnapshotMapper,
            ScenePlanVersionMapper scenePlanVersionMapper,
            GenerationRetryMetadataResolver retryMetadataResolver) {
        this.generationMapper = generationMapper;
        this.sceneMapper = sceneMapper;
        this.reportMapper = reportMapper;
        this.revisionMapper = revisionMapper;
        this.boundedRevisionMapper = boundedRevisionMapper;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.contextSnapshotMapper = contextSnapshotMapper;
        this.assetSourceSnapshotMapper = assetSourceSnapshotMapper;
        this.scenePlanVersionMapper = scenePlanVersionMapper;
        this.retryMetadataResolver = retryMetadataResolver;
    }

    @Autowired
    public void setAgentRuntime(@Lazy AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public EvaluationReportView create(Long chapterId, Long generationId, CreateEvaluationRequest request) {
        ChapterGenerationEntity generation = requireGeneration(chapterId, generationId);
        if (request == null || blank(request.idempotencyKey())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评价请求必须提供 idempotencyKey");
        }
        ChapterGenerationSceneEntity scene = requireScene(generationId, request.generationSceneId());
        FrozenEvaluationSource frozenSource = freezeSource(generation, scene);
        String source = frozenSource.sourceJson();
        EvaluationBindings bindings = bindings(generation, source);
        String fingerprint = inputFingerprint(source);
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
        report.setContextSnapshotId(frozenSource.contextSnapshotId());
        report.setAiTaskId(task.getId());
        report.setIdempotencyKey(request.idempotencyKey());
        report.setInputFingerprint(fingerprint);
        report.setSourceSnapshotJson(source);
        report.setReportStatus(STATUS_QUEUED);
        report.setRulesetVersion(RULESET_VERSION);
        report.setEvaluatorVersion(EVALUATOR_VERSION);
        report.setContentHash(bindings.contentHash());
        report.setBriefFingerprint(bindings.briefFingerprint());
        report.setSourceFingerprint(bindings.sourceFingerprint());
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
    public EvaluationReportView createAutomatic(Long chapterId, Long generationId) {
        ChapterGenerationEntity generation = requireGeneration(chapterId, generationId);
        if (!GENERATION_PREVIEW.equals(generation.getGenerationStatus())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "只有已完成正文候选可以进入强制评价");
        }
        String contentHash = hash(generation.getGeneratedContent() == null ? "" : generation.getGeneratedContent());
        return create(chapterId, generationId,
                new CreateEvaluationRequest(null,
                        "automatic:" + contentHash + ":" + RULESET_VERSION + ":" + EVALUATOR_VERSION));
    }

    @Override
    public EvaluationReportView latest(Long chapterId, Long generationId, Long generationSceneId) {
        requireGeneration(chapterId, generationId);
        ChapterGenerationEvaluationReportEntity report = reportMapper.selectOne(
                new LambdaQueryWrapper<ChapterGenerationEvaluationReportEntity>()
                        .eq(ChapterGenerationEvaluationReportEntity::getGenerationId, generationId)
                        .isNull(generationSceneId == null,
                                ChapterGenerationEvaluationReportEntity::getGenerationSceneId)
                        .eq(generationSceneId != null,
                                ChapterGenerationEvaluationReportEntity::getGenerationSceneId, generationSceneId)
                        .eq(ChapterGenerationEvaluationReportEntity::getDeleted, 0).orderByDesc(ChapterGenerationEvaluationReportEntity::getId)
                        .last("LIMIT 1"));
        return report == null ? null : view(report);
    }

    @Override
    public void requireAdoptable(Long chapterId, Long generationId) {
        ChapterGenerationEntity generation = requireGeneration(chapterId, generationId);
        ChapterGenerationEvaluationReportEntity report = reportMapper.selectOne(
                new LambdaQueryWrapper<ChapterGenerationEvaluationReportEntity>()
                        .eq(ChapterGenerationEvaluationReportEntity::getGenerationId, generationId)
                        .isNull(ChapterGenerationEvaluationReportEntity::getGenerationSceneId)
                        .eq(ChapterGenerationEvaluationReportEntity::getDeleted, 0)
                        .orderByDesc(ChapterGenerationEvaluationReportEntity::getId).last("LIMIT 1"));
        if (!isAdoptableReport(report)) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT,
                    "完整正文候选尚未通过当前独立质量评价，不能采纳");
        }
        if (ASSEMBLY_BOUNDED_REVISION.equals(generation.getContentAssemblyMode())) {
            requireAdoptableBoundedRevision(generation, report);
        }
    }

    private void requireAdoptableBoundedRevision(
            ChapterGenerationEntity generation,
            ChapterGenerationEvaluationReportEntity latestReport) {
        List<BoundedChapterRevisionEntity> matches = boundedRevisionMapper.selectList(
                new LambdaQueryWrapper<BoundedChapterRevisionEntity>()
                        .eq(BoundedChapterRevisionEntity::getResultGenerationId, generation.getId())
                        .eq(BoundedChapterRevisionEntity::getDeleted, 0));
        if (matches.size() != 1) {
            throw boundedRevisionNotAdoptable();
        }
        BoundedChapterRevisionEntity bounded = matches.get(0);
        boolean allowedStatus = BOUNDED_CANDIDATE_READY.equals(bounded.getRevisionStatus())
                || BOUNDED_RE_EVALUATING.equals(bounded.getRevisionStatus());
        boolean exactResult = allowedStatus
                && Integer.valueOf(0).equals(bounded.getDeleted())
                && Objects.equals(bounded.getWorkId(), generation.getWorkId())
                && Objects.equals(bounded.getChapterId(), generation.getChapterId())
                && Objects.equals(bounded.getResultGenerationId(), generation.getId())
                && Objects.equals(bounded.getResultReportId(), latestReport.getId())
                && Objects.equals(bounded.getResultContentHash(), latestReport.getContentHash())
                && Objects.equals(latestReport.getWorkId(), generation.getWorkId())
                && Objects.equals(latestReport.getChapterId(), generation.getChapterId())
                && Objects.equals(latestReport.getGenerationId(), generation.getId())
                && latestReport.getGenerationSceneId() == null
                && STATUS_READY.equals(latestReport.getReportStatus())
                && ("pass".equals(latestReport.getConclusion())
                        || "warning".equals(latestReport.getConclusion()));
        if (!exactResult) {
            throw boundedRevisionNotAdoptable();
        }
    }

    private BusinessException boundedRevisionNotAdoptable() {
        return new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT,
                "#106 有界修订任务或精确重新评价报告尚未达到 candidate_ready，不能采纳");
    }

    @Override
    public EvaluationReportView get(Long chapterId, Long generationId, Long reportId) {
        return view(requireReport(chapterId, generationId, reportId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView retry(Long chapterId, Long generationId, Long reportId, RetryEvaluationRequest request) {
        ChapterGenerationEvaluationReportEntity report = requireReport(chapterId, generationId, reportId);
        RetryMetadata metadata = retryMetadata(report);
        if (request == null || request.expectedAttempt() == null
                || !STATUS_FAILED.equals(report.getReportStatus())
                || !Boolean.TRUE.equals(metadata.retryable())
                || !Objects.equals(request.expectedAttempt(), metadata.currentAttempt())) {
            throw retryConflict();
        }
        int changed = reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>()
                .eq("id", reportId).eq("version", report.getVersion())
                .eq("agent_run_id", report.getAgentRunId()).eq("report_status", STATUS_FAILED)
                .set("report_status", "running")
                .set("conclusion", null).set("error_code", null).set("error_message", null)
                .setSql("version = version + 1"));
        if (changed != 1) {
            throw retryConflict();
        }
        return agentRuntime.retryStep(new RetryAgentStepCommand(
                report.getAgentRunId(), SEMANTIC_EVALUATE, request.expectedAttempt()));
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

    /** 绑定本次独立 evaluator 的可观测模型调用。 */
    @Transactional(rollbackFor = RuntimeException.class)
    public void recordModelCall(Long reportId, Long modelCallId) {
        if (modelCallId == null) {
            return;
        }
        reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>()
                .eq("id", reportId).set("model_call_id", modelCallId).setSql("version = version + 1"));
    }

    public List<EvaluationFinding> deterministicFindings(Long reportId) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        ensureCurrent(report);
        ChapterGenerationSceneEntity scene = report.getGenerationSceneId() == null ? null : sceneMapper.selectById(report.getGenerationSceneId());
        if (scene != null && blank(scene.getGeneratedContent())) {
            return List.of(new EvaluationFinding("empty-scene", "scene_content", "blocking", 1D, "rule", scene.getId(),
                    "全文", null, "场景候选正文为空，不能进入采纳流程", "重新生成该场景"));
        }
        ChapterGenerationEntity generation = generationMapper.selectById(report.getGenerationId());
        if (missingWholeChapter(scene, generation)) {
            return List.of(new EvaluationFinding("empty-chapter", "content_integrity", "blocking", 1D, "rule", null,
                    "全文", null, "整章候选正文为空，不能进入采纳流程", "重新生成整章正文",
                    "完整正文候选", "全文", true, false));
        }
        if (scene == null) {
            return List.of();
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

    /**
     * 返回评价报告已经持久化的模型调用归属，不从运行时输入猜测历史关联。
     *
     * @param reportId 评价报告 ID
     * @return 可空字段保持原始持久化语义的调用归属
     */
    public EvaluationCallOwnership callOwnership(Long reportId) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        return new EvaluationCallOwnership(report.getWorkId(), report.getChapterId(), report.getAiTaskId());
    }

    /** 校验模型 Finding 只能引用当前批次中的已固化场景。 */
    public List<EvaluationFinding> validateSemanticFindings(Long reportId, List<EvaluationFinding> findings) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        ensureCurrent(report);
        if (findings == null || findings.size() > MAX_FINDING_COUNT) {
            throw new EvaluationFindingContractException("invalid_count", "findings");
        }
        java.util.Set<String> allowedFactRefs = allowedStoryFactRefs(report);
        for (int index = 0; index < findings.size(); index++) {
            validateSemanticFinding(report, findings.get(index), index, allowedFactRefs);
        }
        return List.copyOf(findings);
    }

    private void validateSemanticFinding(ChapterGenerationEvaluationReportEntity report, EvaluationFinding finding,
            int index, java.util.Set<String> allowedFactRefs) {
        String base = "findings[" + index + "]";
        if (finding == null) {
            throw new EvaluationFindingContractException("type_mismatch", base);
        }
        requireFindingText(finding.issueKey(), base + ".issueKey", 500);
        requireFindingText(finding.category(), base + ".category", 200);
        requireFindingText(finding.severity(), base + ".severity", 50);
        if (!FINDING_SEVERITIES.contains(finding.severity())) {
            throw new EvaluationFindingContractException("invalid_enum", base + ".severity");
        }
        if (finding.confidence() == null || finding.confidence() < MIN_CONFIDENCE
                || finding.confidence() > MAX_CONFIDENCE) {
            throw new EvaluationFindingContractException("invalid_value", base + ".confidence");
        }
        if (finding.generationSceneId() != null
                && !belongsToGeneration(report.getGenerationId(), finding.generationSceneId())) {
            throw new EvaluationFindingContractException("invalid_reference", base + ".generationSceneId");
        }
        if (finding.storyFactRef() != null && !allowedFactRefs.contains(finding.storyFactRef())) {
            throw new EvaluationFindingContractException("invalid_reference", base + ".storyFactRef");
        }
        requireOptionalFindingText(finding.evidenceRange(), base + ".evidenceRange", 200);
        requireFindingText(finding.summary(), base + ".summary", 500);
        requireOptionalFindingText(finding.violatedSource(), base + ".violatedSource", 500);
        requireOptionalFindingText(finding.impactScope(), base + ".impactScope", 200);
        if (finding.blocksAcceptance() == null) {
            throw new EvaluationFindingContractException("missing_field", base + ".blocksAcceptance");
        }
        if (finding.suitableForAutoRevision() == null) {
            throw new EvaluationFindingContractException("missing_field", base + ".suitableForAutoRevision");
        }
        if (SEVERITY_BLOCKING.equals(finding.severity()) != Boolean.TRUE.equals(finding.blocksAcceptance())) {
            throw new EvaluationFindingContractException("inconsistent_value", base + ".blocksAcceptance");
        }
        if (Boolean.TRUE.equals(finding.suitableForAutoRevision())
                && !Boolean.TRUE.equals(finding.blocksAcceptance())) {
            throw new EvaluationFindingContractException("inconsistent_value", base + ".suitableForAutoRevision");
        }
        if (Boolean.TRUE.equals(finding.blocksAcceptance()) && blank(finding.evidenceRange())) {
            throw new EvaluationFindingContractException("missing_evidence", base + ".evidenceRange");
        }
    }

    private void requireFindingText(String value, String path, int maxLength) {
        if (blank(value)) {
            throw new EvaluationFindingContractException("invalid_value", path);
        }
        requireOptionalFindingText(value, path, maxLength);
    }

    private void requireOptionalFindingText(String value, String path, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new EvaluationFindingContractException("value_too_long", path);
        }
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
        String conclusion = aggregate(findings);
        reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>().eq("id", reportId)
                .set("report_status", STATUS_READY).set("conclusion", conclusion).set("findings_json", json(findings))
                .set("error_code", null).set("error_message", null)
                .setSql("version = version + 1"));
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void fail(Long reportId) {
        fail(reportId, "evaluation_failed", "独立正文评价失败或超时，请安全重试或人工处理");
    }

    /** 记录安全失败摘要并保持正文候选不可采纳。 */
    @Transactional(rollbackFor = RuntimeException.class)
    public void fail(Long reportId, String errorCode, String errorMessage) {
        reportMapper.update(null, new UpdateWrapper<ChapterGenerationEvaluationReportEntity>().eq("id", reportId)
                .notIn("report_status", STATUS_STALE, STATUS_CANCELED).set("report_status", "failed")
                .set("conclusion", CONCLUSION_NEEDS_HUMAN).set("error_code", errorCode)
                .set("error_message", errorMessage)
                .setSql("version = version + 1"));
    }

    /** 判断本报告是否还能启动唯一的一次局部修订。 */
    public boolean shouldRevise(Long reportId, List<EvaluationFinding> findings) {
        ChapterGenerationEvaluationReportEntity report = requireReportById(reportId);
        return report.getGenerationSceneId() != null
                && (report.getRevisionAttempt() == null || report.getRevisionAttempt() == 0)
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

    private FrozenEvaluationSource freezeSource(
            ChapterGenerationEntity generation,
            ChapterGenerationSceneEntity scene) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("generationId", generation.getId());
        source.put("generationVersion", generation.getVersion());
        source.put("generationContentHash", hash(generation.getGeneratedContent() == null ? "" : generation.getGeneratedContent()));
        source.put("generationContent", generation.getGeneratedContent());
        source.put("basisSnapshot", basisSnapshot(generation.getBasisSnapshotJson()));
        source.put("sceneId", scene == null ? null : scene.getId());
        source.put("sceneContentHash", scene == null ? null : scene.getContentHash());
        source.put("sceneContent", scene == null ? null : scene.getGeneratedContent());
        ChapterAssetSourceSnapshotEntity assetSnapshot = scene == null
                ? requireAssetSourceSnapshot(generation) : null;
        source.put("assetSourceSnapshotId", assetSnapshot == null ? null : assetSnapshot.getId());
        source.put("assetSourceSnapshot", assetSourceSnapshot(assetSnapshot));
        Long contextSnapshotId = scene == null
                ? assetSnapshot == null ? null : assetSnapshot.getSourceContextSnapshotId()
                : scene.getContextSnapshotId();
        source.put("contextSnapshotId", contextSnapshotId);
        StoryContextSnapshotEntity contextSnapshot = requireContextSnapshot(contextSnapshotId);
        source.put("contextSnapshotHash", contextSnapshot == null ? null : contextSnapshot.getContentHash());
        source.put("contextSnapshot", contextSnapshot == null ? null : contextSnapshot.getSnapshotJson());
        return new FrozenEvaluationSource(json(source), contextSnapshotId);
    }

    private ChapterAssetSourceSnapshotEntity requireAssetSourceSnapshot(ChapterGenerationEntity generation) {
        if (generation.getSourceSnapshotId() == null) {
            return null;
        }
        ChapterAssetSourceSnapshotEntity snapshot = assetSourceSnapshotMapper.selectById(generation.getSourceSnapshotId());
        if (snapshot == null || Integer.valueOf(1).equals(snapshot.getDeleted())
                || !Objects.equals(generation.getWorkId(), snapshot.getWorkId())
                || !Objects.equals(generation.getChapterId(), snapshot.getChapterId())
                || !"generation".equals(snapshot.getAssetType())
                || !Objects.equals(generation.getId(), snapshot.getAssetId())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "正文候选的资产来源快照不存在或不属于当前正文候选");
        }
        return snapshot;
    }

    private StoryContextSnapshotEntity requireContextSnapshot(Long contextSnapshotId) {
        if (contextSnapshotId == null) {
            return null;
        }
        StoryContextSnapshotEntity snapshot = contextSnapshotMapper.selectById(contextSnapshotId);
        if (snapshot == null || Integer.valueOf(1).equals(snapshot.getDeleted())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "正文评价关联的 Story Context 快照不存在");
        }
        return snapshot;
    }

    private Map<String, Object> assetSourceSnapshot(ChapterAssetSourceSnapshotEntity snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("assetType", snapshot.getAssetType());
        value.put("assetId", snapshot.getAssetId());
        value.put("assetVersion", snapshot.getAssetVersion());
        value.put("sourceConsensusVersionId", snapshot.getSourceConsensusVersionId());
        value.put("sourceNarrativePlanVersionId", snapshot.getSourceNarrativePlanVersionId());
        value.put("sourceOutlineId", snapshot.getSourceOutlineId());
        value.put("sourceOutlineRevision", snapshot.getSourceOutlineRevision());
        value.put("sourceScenePlanVersionId", snapshot.getSourceScenePlanVersionId());
        value.put("sourceContextSnapshotId", snapshot.getSourceContextSnapshotId());
        value.put("sourceContentHash", snapshot.getSourceContentHash());
        return value;
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
        FrozenEvaluationSource frozenSource;
        try {
            frozenSource = freezeSource(generation, scene);
        } catch (BusinessException exception) {
            return false;
        }
        if (report.getContentHash() == null || report.getSourceFingerprint() == null) {
            return inputFingerprint(frozenSource.sourceJson()).equals(report.getInputFingerprint());
        }
        EvaluationBindings current = bindings(generation, frozenSource.sourceJson());
        return Objects.equals(report.getContentHash(), current.contentHash())
                && Objects.equals(report.getBriefFingerprint(), current.briefFingerprint())
                && Objects.equals(report.getSourceFingerprint(), current.sourceFingerprint());
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
            JsonNode content = objectMapper.readTree(report.getSourceSnapshotJson());
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
        RetryMetadata metadata = retryMetadata(report);
        boolean failed = STATUS_FAILED.equals(report.getReportStatus());
        boolean retryable = failed
                && Boolean.TRUE.equals(metadata.retryable());
        return new EvaluationReportView(report.getId(), report.getGenerationId(), report.getGenerationSceneId(), report.getContextSnapshotId(),
                report.getAiTaskId(), report.getAgentRunId(), report.getReportStatus(), report.getConclusion(), findings(report),
                report.getRulesetVersion(), report.getEvaluatorVersion(), report.getContentHash(),
                report.getBriefFingerprint(), report.getSourceFingerprint(), report.getModelCallId(),
                report.getErrorCode(), report.getErrorMessage(), failed ? metadata.currentAttempt() : null, retryable,
                report.getRevisionAttempt(),
                report.getRevisionCandidateId() == null ? null : revisionCandidate(report.getChapterId(), report.getGenerationId(), report.getId()),
                report.getVersion(), report.getGmtCreate(), report.getGmtModified());
    }

    private RetryMetadata retryMetadata(ChapterGenerationEvaluationReportEntity report) {
        RetryMetadata metadata = retryMetadataResolver.resolveOwned(report.getAgentRunId(), SEMANTIC_EVALUATE,
                WORKFLOW_TYPE, report.getWorkId(), report.getChapterId(), report.getAiTaskId());
        return metadata == null ? RetryMetadata.empty() : metadata;
    }

    private BusinessException retryConflict() {
        return new BusinessException(ErrorCode.AGENT_RUN_STATE_CONFLICT,
                "评价重试状态已变化，请重新读取报告后再试");
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

    private String inputFingerprint(String source) {
        return hash(RULESET_VERSION + "\n" + EVALUATOR_VERSION + "\n" + source);
    }

    private boolean isAdoptableReport(ChapterGenerationEvaluationReportEntity report) {
        if (report == null || !STATUS_READY.equals(report.getReportStatus()) || !isCurrent(report)) {
            return false;
        }
        return "pass".equals(report.getConclusion()) || "warning".equals(report.getConclusion());
    }

    private boolean missingWholeChapter(
            ChapterGenerationSceneEntity scene,
            ChapterGenerationEntity generation) {
        return scene == null && (generation == null || blank(generation.getGeneratedContent()));
    }

    private EvaluationBindings bindings(ChapterGenerationEntity generation, String source) {
        String briefFingerprint = null;
        try {
            JsonNode basis = blank(generation.getBasisSnapshotJson())
                    ? null : objectMapper.readTree(generation.getBasisSnapshotJson());
            JsonNode brief = basis == null ? null : basis.get("chapterGenerationBrief");
            briefFingerprint = brief == null ? null : text(brief.get("fingerprint"));
            if (briefFingerprint == null && basis != null) {
                briefFingerprint = text(basis.get("briefFingerprint"));
            }
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "章节生成依据快照无法读取", exception);
        }
        return new EvaluationBindings(
                hash(generation.getGeneratedContent() == null ? "" : generation.getGeneratedContent()),
                briefFingerprint,
                hash(source));
    }

    private JsonNode basisSnapshot(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "章节生成依据快照无法读取", exception);
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() || !node.isValueNode() ? null : node.asText();
    }

    private String aggregate(List<EvaluationFinding> findings) {
        boolean needsHuman = findings.stream().anyMatch(item -> Boolean.TRUE.equals(item.blocksAcceptance())
                && (item.confidence() < 0.8D || !Boolean.TRUE.equals(item.suitableForAutoRevision())
                || "source_conflict".equals(item.category()) || "planning_change".equals(item.category())
                || "authority_change".equals(item.category())));
        if (needsHuman) {
            return CONCLUSION_NEEDS_HUMAN;
        }
        boolean needsRevision = findings.stream().anyMatch(item -> Boolean.TRUE.equals(item.blocksAcceptance())
                && item.confidence() >= 0.8D && Boolean.TRUE.equals(item.suitableForAutoRevision()));
        if (needsRevision) {
            return CONCLUSION_NEEDS_REVISION;
        }
        return findings.stream().anyMatch(item -> "warning".equals(item.severity())) ? "warning" : "pass";
    }

    private record EvaluationBindings(String contentHash, String briefFingerprint, String sourceFingerprint) {
    }

    private record FrozenEvaluationSource(String sourceJson, Long contextSnapshotId) {
    }

    /** 评价 Provider 调用可安全持久化的业务归属。 */
    public record EvaluationCallOwnership(Long workId, Long chapterId, Long aiTaskId) {
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
