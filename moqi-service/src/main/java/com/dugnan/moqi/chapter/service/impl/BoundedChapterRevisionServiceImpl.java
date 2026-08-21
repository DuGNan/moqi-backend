package com.dugnan.moqi.chapter.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
import com.dugnan.moqi.chapter.dto.BoundedChapterRevisionModels.BoundedRevisionView;
import com.dugnan.moqi.chapter.dto.BoundedChapterRevisionModels.CreateBoundedRevisionRequest;
import com.dugnan.moqi.chapter.dto.BoundedChapterRevisionModels.RetryBoundedRevisionRequest;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationFinding;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationReportView;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.BoundedChapterRevisionEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.BoundedChapterRevisionMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.service.BoundedChapterRevisionService;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.chapter.service.ProseCandidateMaterializationService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.sourcechain.entity.ChapterAssetSourceSnapshotEntity;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 根据整章评价创建一次可恢复修订任务，并保存为新的未采纳正文候选。
 */
@Service
public class BoundedChapterRevisionServiceImpl implements BoundedChapterRevisionService {
    public static final String WORKFLOW_TYPE = "bounded_chapter_revision_v1";
    public static final String TEMPLATE_VERSION = "bounded-chapter-revision-v1";
    private static final String LOCAL_USER = "local-user";
    private static final String STEP_REVISE = "revise";
    private static final String STEP_START_RE_EVALUATION = "start_re_evaluation";
    private static final String REPORT_READY = "ready";
    private static final String REPORT_FAILED = "failed";
    private static final int MAX_FINDINGS = 10;
    private static final int MAX_REVISION_LENGTH = 50000;
    private static final List<String> HUMAN_CATEGORIES = List.of(
            "source_conflict", "planning_change", "authority_change");

    private final ChapterGenerationMapper generationMapper;
    private final ChapterGenerationEvaluationReportMapper reportMapper;
    private final BoundedChapterRevisionMapper revisionMapper;
    private final AiTaskMapper taskMapper;
    private final ChapterAssetSourceSnapshotMapper sourceSnapshotMapper;
    private final ObjectMapper objectMapper;
    private AgentRuntime agentRuntime;
    private GenerationEvaluationService evaluationService;
    private ProseCandidateMaterializationService materializationService;

    public BoundedChapterRevisionServiceImpl(
            ChapterGenerationMapper generationMapper,
            ChapterGenerationEvaluationReportMapper reportMapper,
            BoundedChapterRevisionMapper revisionMapper,
            AiTaskMapper taskMapper,
            ChapterAssetSourceSnapshotMapper sourceSnapshotMapper,
            ObjectMapper objectMapper) {
        this.generationMapper = generationMapper;
        this.reportMapper = reportMapper;
        this.revisionMapper = revisionMapper;
        this.taskMapper = taskMapper;
        this.sourceSnapshotMapper = sourceSnapshotMapper;
        this.objectMapper = objectMapper;
    }

    @Autowired
    public void setAgentRuntime(@Lazy AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @Autowired
    public void setEvaluationService(@Lazy GenerationEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @Autowired
    public void setMaterializationService(ProseCandidateMaterializationService materializationService) {
        this.materializationService = materializationService;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public BoundedRevisionView create(
            Long chapterId,
            Long generationId,
            CreateBoundedRevisionRequest request) {
        ChapterGenerationEntity generation = requireGeneration(chapterId, generationId);
        if (request == null || request.evaluationReportId() == null || blank(request.idempotencyKey())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "有界修订必须提供评价报告和 idempotencyKey");
        }
        ChapterGenerationEvaluationReportEntity report = requireWholeReport(
                chapterId, generationId, request.evaluationReportId());
        if (!Objects.equals(report.getContentHash(), hash(generation.getGeneratedContent()))) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "评价报告已不匹配当前正文候选");
        }
        BoundedChapterRevisionEntity idempotent = findIdempotent(generationId, request.idempotencyKey());
        if (idempotent != null) {
            if (!Objects.equals(idempotent.getSourceReportId(), report.getId())) {
                throw new BusinessException(ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT, "幂等键已绑定其他评价报告");
            }
            return view(idempotent);
        }
        if (revisionMapper.selectOne(new LambdaQueryWrapper<BoundedChapterRevisionEntity>()
                .eq(BoundedChapterRevisionEntity::getSourceGenerationId, generationId)
                .eq(BoundedChapterRevisionEntity::getDeleted, 0).last("LIMIT 1")) != null) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "P0 每个正文候选最多自动修订一次");
        }
        List<EvaluationFinding> findings = findings(report);
        StopDecision stop = stopDecision(report, findings);
        List<EvaluationFinding> selected = eligible(findings);
        List<EvaluationFinding> recorded = stop == null ? selected : findings;
        Map<String, Object> brief = revisionBrief(generation, report, recorded);
        BoundedChapterRevisionEntity revision = new BoundedChapterRevisionEntity();
        revision.setWorkId(generation.getWorkId());
        revision.setChapterId(chapterId);
        revision.setSourceGenerationId(generationId);
        revision.setSourceReportId(report.getId());
        revision.setIdempotencyKey(request.idempotencyKey());
        revision.setRevisionStatus(stop == null ? "queued" : "needs_human");
        revision.setStopReason(stop == null ? null : stop.reason());
        revision.setFindingKeysJson(json(recorded.stream().map(EvaluationFinding::issueKey).toList()));
        revision.setRevisionBriefJson(json(brief));
        revision.setSourceContentHash(report.getContentHash());
        revision.setRevisionAttempt(0);
        revision.setDeleted(0);
        revision.setVersion(0);
        if (stop != null) {
            revisionMapper.insert(revision);
            return view(revision);
        }
        AiTaskEntity task = createTask(generation, report, request.idempotencyKey());
        revision.setAiTaskId(task.getId());
        revisionMapper.insert(revision);
        AgentRunView run = agentRuntime.start(new StartAgentRunCommand(
                LOCAL_USER, generation.getWorkId(), chapterId, WORKFLOW_TYPE,
                request.idempotencyKey(), generation.getVersion().longValue(),
                Map.of("revisionId", revision.getId(), "aiTaskId", task.getId()), task.getId()));
        revisionMapper.update(null, new UpdateWrapper<BoundedChapterRevisionEntity>()
                .eq("id", revision.getId()).eq("version", 0)
                .set("agent_run_id", run.runId()).setSql("version = version + 1"));
        revision.setAgentRunId(run.runId());
        revision.setVersion(1);
        return view(revision);
    }

    @Override
    public BoundedRevisionView get(Long chapterId, Long generationId, Long revisionId) {
        return view(requireRevision(chapterId, generationId, revisionId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView retry(Long chapterId, Long generationId, Long revisionId,
            RetryBoundedRevisionRequest request) {
        BoundedChapterRevisionEntity revision = requireRevision(chapterId, generationId, revisionId);
        if (revision.getAgentRunId() == null || request == null || request.expectedAttempt() == null
                || !"failed".equals(revision.getRevisionStatus())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "当前有界修订不能重试");
        }
        revisionMapper.update(null, new UpdateWrapper<BoundedChapterRevisionEntity>()
                .eq("id", revisionId).eq("revision_status", "failed")
                .set("revision_status", "running").set("error_code", null).set("error_message", null)
                .setSql("version = version + 1"));
        AgentRunView run = agentRuntime.load(revision.getAgentRunId(), LOCAL_USER);
        if (!List.of(STEP_REVISE, STEP_START_RE_EVALUATION).contains(run.currentStepKey())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "失败步骤不支持安全重试");
        }
        return agentRuntime.retryStep(new RetryAgentStepCommand(
                revision.getAgentRunId(), run.currentStepKey(), request.expectedAttempt()));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView cancel(Long chapterId, Long generationId, Long revisionId) {
        BoundedChapterRevisionEntity revision = requireRevision(chapterId, generationId, revisionId);
        if (revision.getAgentRunId() == null) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "有界修订没有可取消的运行");
        }
        AgentRunView run = agentRuntime.cancel(revision.getAgentRunId());
        revisionMapper.update(null, new UpdateWrapper<BoundedChapterRevisionEntity>()
                .eq("id", revisionId).in("revision_status", "queued", "running")
                .set("revision_status", "canceled").set("stop_reason", "user_abandoned")
                .setSql("version = version + 1"));
        return run;
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void markRunning(Long revisionId) {
        revisionMapper.update(null, new UpdateWrapper<BoundedChapterRevisionEntity>()
                .eq("id", revisionId).eq("revision_status", "queued")
                .set("revision_status", "running").setSql("version = version + 1"));
    }

    public Map<String, Object> workflowInput(Long revisionId) {
        BoundedChapterRevisionEntity revision = requireRevisionById(revisionId);
        ChapterGenerationEntity generation = generationMapper.selectById(revision.getSourceGenerationId());
        return Map.of("revisionBrief", readMap(revision.getRevisionBriefJson()),
                "originalContent", generation.getGeneratedContent());
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public Long persistCandidate(Long revisionId, String revisedContent, Long modelCallId) {
        BoundedChapterRevisionEntity revision = requireRevisionById(revisionId);
        if (revision.getRevisionAttempt() != null && revision.getRevisionAttempt() >= 1) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "P0 有界修订预算已耗尽");
        }
        if (blank(revisedContent) || revisedContent.length() > MAX_REVISION_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "修订正文为空或超过预算");
        }
        ChapterGenerationEntity source = generationMapper.selectById(revision.getSourceGenerationId());
        if (Objects.equals(hash(source.getGeneratedContent()), hash(revisedContent.trim()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "修订结果与原正文相同，不能创建无变化候选");
        }
        ChapterGenerationEntity candidate = copyCandidate(source, revisedContent.trim(), revision, modelCallId);
        generationMapper.insert(candidate);
        Long snapshotId = copySourceSnapshot(source, candidate);
        candidate.setSourceSnapshotId(snapshotId);
        generationMapper.updateById(candidate);
        String resultHash = hash(candidate.getGeneratedContent());
        revisionMapper.update(null, new UpdateWrapper<BoundedChapterRevisionEntity>()
                .eq("id", revisionId).eq("revision_attempt", 0)
                .set("result_generation_id", candidate.getId()).set("result_content_hash", resultHash)
                .set("revision_model_call_id", modelCallId).set("revision_attempt", 1)
                .set("revision_status", "re_evaluating").setSql("version = version + 1"));
        return candidate.getId();
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public Long startReEvaluation(Long revisionId) {
        BoundedChapterRevisionEntity revision = requireRevisionById(revisionId);
        if (revision.getResultReportId() != null) {
            return revision.getResultReportId();
        }
        ChapterGenerationEntity candidate = generationMapper.selectById(revision.getResultGenerationId());
        materializationService.materialize(candidate);
        EvaluationReportView report;
        try {
            report = evaluationService.createAutomatic(revision.getChapterId(), revision.getResultGenerationId());
            materializationService.markQualityRequested(revision.getResultGenerationId());
        } catch (RuntimeException exception) {
            materializationService.markQualityUnavailable(revision.getResultGenerationId());
            throw exception;
        }
        revisionMapper.update(null, new UpdateWrapper<BoundedChapterRevisionEntity>()
                .eq("id", revisionId).isNull("result_report_id")
                .set("result_report_id", report.id()).set("revision_status", "re_evaluating")
                .setSql("version = version + 1"));
        return report.id();
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void completeWorkflow(Long revisionId) {
        BoundedChapterRevisionEntity revision = requireRevisionById(revisionId);
        if (revision.getResultReportId() == null) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "修订候选尚未启动重新评价");
        }
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void fail(Long revisionId, String stepKey) {
        revisionMapper.update(null, new UpdateWrapper<BoundedChapterRevisionEntity>()
                .eq("id", revisionId).notIn("revision_status", "canceled", "needs_human")
                .set("revision_status", "failed").set("stop_reason", "provider_or_evaluation_start_failed")
                .set("error_code", "bounded_revision_failed")
                .set("error_message", "有界修订步骤 " + stepKey + " 失败，未采纳任何正文")
                .setSql("version = version + 1"));
    }

    private StopDecision stopDecision(
            ChapterGenerationEvaluationReportEntity report,
            List<EvaluationFinding> findings) {
        if (!REPORT_READY.equals(report.getReportStatus())) {
            return new StopDecision("evaluation_not_ready");
        }
        if (findings.stream().anyMatch(item -> HUMAN_CATEGORIES.contains(item.category()))) {
            return new StopDecision("source_conflict");
        }
        if (findings.stream().anyMatch(this::requiresHumanDecision)) {
            return new StopDecision("low_confidence_or_human_boundary");
        }
        List<EvaluationFinding> selected = eligible(findings);
        if (selected.isEmpty()) {
            return new StopDecision("no_evidence_backed_finding");
        }
        if (selected.size() > MAX_FINDINGS) {
            return new StopDecision("finding_budget_exhausted");
        }
        return null;
    }

    private List<EvaluationFinding> eligible(List<EvaluationFinding> findings) {
        return findings.stream().filter(item -> Boolean.TRUE.equals(item.blocksAcceptance()))
                .filter(item -> Boolean.TRUE.equals(item.suitableForAutoRevision()))
                .filter(item -> item.confidence() != null && item.confidence() >= 0.8D)
                .filter(item -> !blank(item.evidenceRange())).limit(MAX_FINDINGS + 1L).toList();
    }

    private Map<String, Object> revisionBrief(
            ChapterGenerationEntity generation,
            ChapterGenerationEvaluationReportEntity report,
            List<EvaluationFinding> findings) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("sourceGenerationId", generation.getId());
        brief.put("sourceContentHash", report.getContentHash());
        brief.put("evaluationReportId", report.getId());
        brief.put("evaluationSourceFingerprint", report.getSourceFingerprint());
        brief.put("mustPreserve", List.of("冻结 Brief 与来源事实", "未命中证据范围的有效正文", "原始正文和正式正文"));
        brief.put("findings", findings);
        brief.put("maxAutomaticRounds", 1);
        return brief;
    }

    private AiTaskEntity createTask(
            ChapterGenerationEntity generation,
            ChapterGenerationEvaluationReportEntity report,
            String idempotencyKey) {
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(WORKFLOW_TYPE);
        task.setTaskStatus("queued");
        task.setWorkId(generation.getWorkId());
        task.setChapterId(generation.getChapterId());
        task.setTaskInputJson(json(Map.of("sourceGenerationId", generation.getId(),
                "sourceReportId", report.getId(), "idempotencyKey", idempotencyKey)));
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);
        return task;
    }

    private ChapterGenerationEntity copyCandidate(
            ChapterGenerationEntity source,
            String content,
            BoundedChapterRevisionEntity revision,
            Long modelCallId) {
        ChapterGenerationEntity target = new ChapterGenerationEntity();
        target.setWorkId(source.getWorkId());
        target.setChapterId(source.getChapterId());
        target.setBriefId(source.getBriefId());
        target.setOutlineId(source.getOutlineId());
        target.setOutlineRevision(source.getOutlineRevision());
        target.setChapterPlanVersionId(source.getChapterPlanVersionId());
        target.setBaseGenerationId(source.getId());
        target.setGenerationStatus("preview");
        target.setGenerationMode(source.getGenerationMode());
        target.setSelectionMode(source.getSelectionMode());
        target.setIdempotencyKey("bounded-revision:" + revision.getId());
        target.setLengthPreset(source.getLengthPreset());
        target.setCustomWordCount(source.getCustomWordCount());
        target.setBasisSnapshotJson(source.getBasisSnapshotJson());
        target.setExecutionConfigJson(source.getExecutionConfigJson());
        target.setGeneratedContent(content);
        target.setContentAssemblyMode("bounded_revision");
        target.setCohesionStatus(source.getCohesionStatus());
        target.setGenerationModelCallId(modelCallId);
        target.setGenerationTemplateVersion(TEMPLATE_VERSION);
        target.setGenerationFinishReason("stop");
        target.setWordCount(content.codePointCount(0, content.length()));
        target.setAiTaskId(revision.getAiTaskId());
        target.setAgentRunId(revision.getAgentRunId());
        target.setValidityStatus(source.getValidityStatus());
        target.setValidityReasonCodesJson(source.getValidityReasonCodesJson());
        target.setDeleted(0);
        target.setVersion(0);
        return target;
    }

    private Long copySourceSnapshot(ChapterGenerationEntity source, ChapterGenerationEntity target) {
        if (source.getSourceSnapshotId() == null) {
            return null;
        }
        ChapterAssetSourceSnapshotEntity original = sourceSnapshotMapper.selectById(source.getSourceSnapshotId());
        if (original == null || Integer.valueOf(1).equals(original.getDeleted())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "原正文候选来源快照不存在");
        }
        if (!Objects.equals(source.getWorkId(), original.getWorkId())
                || !Objects.equals(source.getChapterId(), original.getChapterId())
                || !"generation".equals(original.getAssetType())
                || !Objects.equals(source.getId(), original.getAssetId())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "原正文候选来源快照归属不一致");
        }
        ChapterAssetSourceSnapshotEntity snapshot = new ChapterAssetSourceSnapshotEntity();
        snapshot.setWorkId(target.getWorkId());
        snapshot.setChapterId(target.getChapterId());
        snapshot.setAssetType("generation");
        snapshot.setAssetId(target.getId());
        snapshot.setAssetVersion(0);
        snapshot.setSourceConsensusVersionId(original.getSourceConsensusVersionId());
        snapshot.setSourceNarrativePlanVersionId(original.getSourceNarrativePlanVersionId());
        snapshot.setSourceOutlineId(original.getSourceOutlineId());
        snapshot.setSourceOutlineRevision(original.getSourceOutlineRevision());
        snapshot.setSourceScenePlanVersionId(original.getSourceScenePlanVersionId());
        snapshot.setSourceContextSnapshotId(original.getSourceContextSnapshotId());
        snapshot.setSourceContentHash(original.getSourceContentHash());
        snapshot.setDeleted(0);
        snapshot.setVersion(0);
        sourceSnapshotMapper.insert(snapshot);
        return snapshot.getId();
    }

    private ChapterGenerationEntity requireGeneration(Long chapterId, Long generationId) {
        ChapterGenerationEntity generation = generationMapper.selectById(generationId);
        if (generation == null || Integer.valueOf(1).equals(generation.getDeleted())
                || !Objects.equals(chapterId, generation.getChapterId())
                || !"preview".equals(generation.getGenerationStatus())
                || blank(generation.getGeneratedContent())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "只能修订当前未采纳正文候选");
        }
        return generation;
    }

    private ChapterGenerationEvaluationReportEntity requireWholeReport(
            Long chapterId,
            Long generationId,
            Long reportId) {
        ChapterGenerationEvaluationReportEntity report = reportMapper.selectById(reportId);
        if (report == null || Integer.valueOf(1).equals(report.getDeleted())
                || !Objects.equals(chapterId, report.getChapterId())
                || !Objects.equals(generationId, report.getGenerationId())
                || report.getGenerationSceneId() != null
                || blank(report.getContentHash()) || blank(report.getSourceFingerprint())
                || !List.of("needs_revision", "needs_human").contains(report.getConclusion())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "必须使用当前整章阻塞评价报告");
        }
        return report;
    }

    private BoundedChapterRevisionEntity findIdempotent(Long generationId, String key) {
        return revisionMapper.selectOne(new LambdaQueryWrapper<BoundedChapterRevisionEntity>()
                .eq(BoundedChapterRevisionEntity::getSourceGenerationId, generationId)
                .eq(BoundedChapterRevisionEntity::getIdempotencyKey, key)
                .eq(BoundedChapterRevisionEntity::getDeleted, 0));
    }

    private BoundedChapterRevisionEntity requireRevision(Long chapterId, Long generationId, Long revisionId) {
        BoundedChapterRevisionEntity revision = requireRevisionById(revisionId);
        if (!Objects.equals(chapterId, revision.getChapterId())
                || !Objects.equals(generationId, revision.getSourceGenerationId())) {
            throw new BusinessException(ErrorCode.GENERATION_NOT_FOUND, "有界修订任务不属于当前正文候选");
        }
        return revision;
    }

    private BoundedChapterRevisionEntity requireRevisionById(Long revisionId) {
        BoundedChapterRevisionEntity revision = revisionMapper.selectById(revisionId);
        if (revision == null || Integer.valueOf(1).equals(revision.getDeleted())) {
            throw new BusinessException(ErrorCode.GENERATION_NOT_FOUND, "有界修订任务不存在");
        }
        return revision;
    }

    private BoundedRevisionView view(BoundedChapterRevisionEntity item) {
        String status = item.getRevisionStatus();
        String stopReason = item.getStopReason();
        if (item.getResultReportId() != null && evaluationService != null) {
            EvaluationReportView report = evaluationService.get(
                    item.getChapterId(), item.getResultGenerationId(), item.getResultReportId());
            if (REPORT_FAILED.equals(report.reportStatus())) {
                status = "needs_human";
                stopReason = "re_evaluation_failed";
            } else if (REPORT_READY.equals(report.reportStatus())) {
                status = List.of("pass", "warning").contains(report.conclusion())
                        ? "candidate_ready" : "needs_human";
                stopReason = "candidate_ready".equals(status) ? "automatic_round_limit_reached"
                        : "re_evaluation_requires_human";
            }
        }
        return new BoundedRevisionView(item.getId(), item.getSourceGenerationId(), item.getSourceReportId(),
                item.getResultGenerationId(), item.getResultReportId(), item.getAiTaskId(), item.getAgentRunId(),
                status, stopReason, readList(item.getFindingKeysJson()), readMap(item.getRevisionBriefJson()),
                item.getSourceContentHash(), item.getResultContentHash(), item.getRevisionModelCallId(),
                item.getRevisionAttempt(), item.getErrorCode(), item.getErrorMessage(), item.getVersion(),
                item.getGmtCreate(), item.getGmtModified());
    }

    private List<EvaluationFinding> findings(ChapterGenerationEvaluationReportEntity report) {
        try {
            return objectMapper.readValue(report.getFindingsJson(), new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "评价 Finding 无法读取", exception);
        }
    }

    private boolean requiresHumanDecision(EvaluationFinding finding) {
        if (!Boolean.TRUE.equals(finding.blocksAcceptance())) {
            return false;
        }
        return finding.confidence() == null || finding.confidence() < 0.8D
                || !Boolean.TRUE.equals(finding.suitableForAutoRevision());
    }

    private List<String> readList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "修订任务书无法读取", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "修订数据无法序列化", exception);
        }
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算修订正文哈希", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record StopDecision(String reason) {
    }
}
