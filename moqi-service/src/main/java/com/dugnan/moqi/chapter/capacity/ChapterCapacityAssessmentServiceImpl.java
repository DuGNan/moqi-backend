package com.dugnan.moqi.chapter.capacity;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityCompiler.CompiledCapacity;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CapacityAssessmentView;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CapacityResult;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CreateAssessmentRequest;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.EventWeight;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.RetryAssessmentRequest;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterCapacityAssessmentEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterCapacityAssessmentMapper;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PublishedScenePlanQueryPort;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 实现章节容量评估的冻结、持久化、恢复与正文生成门禁。
 */
@Service
public class ChapterCapacityAssessmentServiceImpl implements ChapterCapacityAssessmentService {

    public static final String WORKFLOW_TYPE = "chapter_capacity_assessment_v1";
    static final String SEMANTIC_STEP = "semantic_assess";
    private static final String LOCAL_USER = "local-user";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELED = "canceled";
    private static final Set<String> RESULT_STATUSES = Set.of(
            ChapterCapacityModels.RESULT_FITS,
            ChapterCapacityModels.RESULT_TOO_DENSE,
            ChapterCapacityModels.RESULT_TOO_THIN,
            ChapterCapacityModels.RESULT_REQUIRES_LONG_CONTEXT);

    private final ChapterMapper chapterMapper;
    private final ChapterCapacityAssessmentMapper assessmentMapper;
    private final AiTaskMapper taskMapper;
    private final PublishedScenePlanQueryPort planQueryPort;
    private final ChapterGenerationBriefService briefService;
    private final ChapterGenerationLengthPolicy lengthPolicy;
    private final ChapterCapacityCompiler compiler;
    private final ObjectMapper objectMapper;
    private final AgentRuntime agentRuntime;

    public ChapterCapacityAssessmentServiceImpl(
            ChapterMapper chapterMapper,
            ChapterCapacityAssessmentMapper assessmentMapper,
            AiTaskMapper taskMapper,
            PublishedScenePlanQueryPort planQueryPort,
            ChapterGenerationBriefService briefService,
            ChapterGenerationLengthPolicy lengthPolicy,
            ChapterCapacityCompiler compiler,
            ObjectMapper objectMapper,
            @Lazy AgentRuntime agentRuntime) {
        this.chapterMapper = chapterMapper;
        this.assessmentMapper = assessmentMapper;
        this.taskMapper = taskMapper;
        this.planQueryPort = planQueryPort;
        this.briefService = briefService;
        this.lengthPolicy = lengthPolicy;
        this.compiler = compiler;
        this.objectMapper = objectMapper;
        this.agentRuntime = agentRuntime;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public CapacityAssessmentView create(Long chapterId, CreateAssessmentRequest request) {
        validateCreateRequest(request);
        ChapterEntity chapter = requireChapter(chapterId);
        ChapterPlanView plan = request.scenePlanNo() == null
                ? planQueryPort.loadCurrent(chapterId)
                : planQueryPort.loadPublished(chapterId, request.scenePlanNo());
        ChapterGenerationBrief brief = briefService.compile(chapterId, plan);
        String preset = lengthPolicy.normalizePreset(request.lengthPreset());
        int targetWordCount = lengthPolicy.resolveTargetWordCount(preset, request.customWordCount());
        CompiledCapacity compiled = compiler.compile(plan, brief, targetWordCount);
        ChapterCapacityAssessmentEntity existing = findByIdempotency(chapterId, request.idempotencyKey());
        if (existing != null) {
            if (!compiled.fingerprint().equals(existing.getInputFingerprint())) {
                throw new BusinessException(ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT,
                        "容量评估幂等键已绑定不同输入");
            }
            return view(existing);
        }
        AiTaskEntity task = createTask(chapter, plan, compiled);
        ChapterCapacityAssessmentEntity assessment = new ChapterCapacityAssessmentEntity();
        assessment.setWorkId(chapter.getWorkId());
        assessment.setChapterId(chapterId);
        assessment.setChapterPlanVersionId(plan.id());
        assessment.setScenePlanNo(plan.planNo());
        assessment.setTargetWordCount(targetWordCount);
        assessment.setLengthPreset(preset);
        assessment.setCustomWordCount("custom".equals(preset) ? request.customWordCount() : null);
        assessment.setIdempotencyKey(request.idempotencyKey());
        assessment.setInputFingerprint(compiled.fingerprint());
        assessment.setBriefTemplateVersion(brief.templateVersion());
        assessment.setBriefFingerprint(brief.fingerprint());
        assessment.setSourceSnapshotJson(json(compiled.sourceSnapshot()));
        assessment.setAssessmentStatus(STATUS_QUEUED);
        assessment.setResultJson(json(compiled.fallback()));
        assessment.setEvaluatorVersion(ChapterCapacityCompiler.EVALUATOR_VERSION);
        assessment.setAiTaskId(task.getId());
        assessment.setDeleted(0);
        assessment.setVersion(0);
        assessmentMapper.insert(assessment);
        AgentRunView run = agentRuntime.start(new StartAgentRunCommand(
                LOCAL_USER, chapter.getWorkId(), chapterId, WORKFLOW_TYPE, request.idempotencyKey(),
                plan.version().longValue(), Map.of(
                        "assessmentId", assessment.getId(),
                        "workId", chapter.getWorkId(),
                        "aiTaskId", task.getId()), task.getId()));
        int updated = assessmentMapper.update(null, new UpdateWrapper<ChapterCapacityAssessmentEntity>()
                .eq("id", assessment.getId()).eq("version", assessment.getVersion())
                .set("agent_run_id", run.runId()).set("version", assessment.getVersion() + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (updated != 1) {
            throw conflict("容量评估关联运行任务时发生并发冲突");
        }
        assessment.setAgentRunId(run.runId());
        assessment.setVersion(assessment.getVersion() + 1);
        return view(assessment);
    }

    @Override
    public CapacityAssessmentView get(Long assessmentId) {
        return view(requireAssessment(assessmentId));
    }

    @Override
    public AgentRunView retry(Long assessmentId, RetryAssessmentRequest request) {
        ChapterCapacityAssessmentEntity assessment = requireAssessment(assessmentId);
        if (!STATUS_FAILED.equals(assessment.getAssessmentStatus()) || assessment.getAgentRunId() == null
                || request == null || request.expectedAttempt() == null) {
            throw conflict("当前容量评估不能重试");
        }
        return agentRuntime.retryStep(new RetryAgentStepCommand(
                assessment.getAgentRunId(), SEMANTIC_STEP, request.expectedAttempt()));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView cancel(Long assessmentId) {
        ChapterCapacityAssessmentEntity assessment = requireAssessment(assessmentId);
        if (assessment.getAgentRunId() == null) {
            throw conflict("容量评估未关联运行任务");
        }
        AgentRunView run = agentRuntime.cancel(assessment.getAgentRunId());
        assessmentMapper.update(null, new UpdateWrapper<ChapterCapacityAssessmentEntity>()
                .eq("id", assessmentId).eq("version", assessment.getVersion())
                .in("assessment_status", STATUS_QUEUED, STATUS_RUNNING)
                .set("assessment_status", STATUS_CANCELED).setSql("version = version + 1")
                .set("gmt_modified", LocalDateTime.now()));
        return run;
    }

    @Override
    public Map<String, Object> resolveForGeneration(
            ChapterPlanView plan,
            ChapterGenerationBrief brief,
            int targetWordCount,
            Long assessmentId,
            String decision) {
        CompiledCapacity compiled = compiler.compile(plan, brief, targetWordCount);
        CapacityResult result;
        Long resolvedAssessmentId = assessmentId;
        if (assessmentId == null) {
            result = compiled.fallback();
            if (!Set.of(ChapterCapacityModels.RESULT_FITS, ChapterCapacityModels.RESULT_TOO_THIN)
                    .contains(result.status())) {
                throw new BusinessException(ErrorCode.CHAPTER_CAPACITY_ASSESSMENT_REQUIRED,
                        "当前章节容量需要先完成评估并由作者选择后续动作");
            }
        } else {
            ChapterCapacityAssessmentEntity assessment = requireAssessment(assessmentId);
            if (!STATUS_READY.equals(assessment.getAssessmentStatus())
                    || !plan.chapterId().equals(assessment.getChapterId())
                    || !plan.id().equals(assessment.getChapterPlanVersionId())
                    || targetWordCount != assessment.getTargetWordCount()
                    || !brief.fingerprint().equals(assessment.getBriefFingerprint())) {
                throw new BusinessException(ErrorCode.CHAPTER_CAPACITY_ASSESSMENT_STALE,
                        "容量评估与当前章节规划、Brief 或目标篇幅不一致");
            }
            result = result(assessment);
        }
        if (ChapterCapacityModels.RESULT_TOO_DENSE.equals(result.status())
                && !ChapterCapacityModels.DECISION_CONTINUE_LONG_CHAPTER.equals(decision)) {
            throw new BusinessException(ErrorCode.CHAPTER_CAPACITY_DECISION_REQUIRED,
                    "过密章节必须由作者显式选择继续长章，或返回修改规划/拆章");
        }
        if (ChapterCapacityModels.RESULT_REQUIRES_LONG_CONTEXT.equals(result.status())) {
            throw new BusinessException(ErrorCode.CHAPTER_CAPACITY_LONG_CONTEXT_REQUIRED,
                    "当前 Provider 上下文容量不足；请保留该评估作为 #112 的触发证据");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("assessmentId", resolvedAssessmentId);
        snapshot.put("inputFingerprint", assessmentId == null ? compiled.fingerprint()
                : requireAssessment(assessmentId).getInputFingerprint());
        snapshot.put("briefFingerprint", brief.fingerprint());
        snapshot.put("decision", decision);
        snapshot.put("result", result);
        return snapshot;
    }

    public void markRunning(Long assessmentId) {
        ChapterCapacityAssessmentEntity assessment = requireAssessment(assessmentId);
        assessmentMapper.update(null, new UpdateWrapper<ChapterCapacityAssessmentEntity>()
                .eq("id", assessmentId).eq("version", assessment.getVersion())
                .eq("assessment_status", STATUS_QUEUED).set("assessment_status", STATUS_RUNNING)
                .set("error_code", null).set("error_message", null).setSql("version = version + 1")
                .set("gmt_modified", LocalDateTime.now()));
    }

    public String semanticSource(Long assessmentId) {
        return requireAssessment(assessmentId).getSourceSnapshotJson();
    }

    public String inputFingerprint(Long assessmentId) {
        return requireAssessment(assessmentId).getInputFingerprint();
    }

    public CapacityResult fallback(Long assessmentId, String reason) {
        CapacityResult fallback = result(requireAssessment(assessmentId));
        return new CapacityResult(fallback.status(), fallback.suggestedMinimumWordCount(),
                fallback.suggestedMaximumWordCount(), fallback.reasons(), fallback.eventWeights(),
                fallback.compressibleItems(), fallback.nonCompressibleCausalNodes(), fallback.splitSuggestions(),
                fallback.availableActions(), "fallback", reason, fallback.longContextRequired());
    }

    public CapacityResult validateSemantic(Long assessmentId, CapacityResult candidate, Integer providerContextTokens) {
        CapacityResult fallback = result(requireAssessment(assessmentId));
        if (candidate == null || !RESULT_STATUSES.contains(candidate.status())
                || candidate.suggestedMinimumWordCount() == null || candidate.suggestedMaximumWordCount() == null
                || candidate.suggestedMinimumWordCount() < 500
                || candidate.suggestedMaximumWordCount() < candidate.suggestedMinimumWordCount()) {
            throw new IllegalArgumentException("模型容量评估不符合结构化契约");
        }
        Set<String> allowedKeys = fallback.eventWeights().stream().map(EventWeight::eventKey)
                .collect(java.util.stream.Collectors.toSet());
        boolean hasUnknownEvent = candidate.eventWeights().stream()
                .anyMatch(item -> !allowedKeys.contains(item.eventKey()));
        if (hasUnknownEvent) {
            throw new IllegalArgumentException("模型容量评估引用了不存在的事件");
        }
        if (requiresLongContext(assessmentId, providerContextTokens)) {
            return new CapacityResult(ChapterCapacityModels.RESULT_REQUIRES_LONG_CONTEXT,
                    candidate.suggestedMinimumWordCount(),
                    candidate.suggestedMaximumWordCount(), append(candidate.reasons(), "当前 Provider 上下文窗口不足"),
                    candidate.eventWeights(), candidate.compressibleItems(), candidate.nonCompressibleCausalNodes(),
                    candidate.splitSuggestions(), List.of("switch_provider", "reduce_context", "plan_issue_112"),
                    "model", null, true);
        }
        return new CapacityResult(candidate.status(), candidate.suggestedMinimumWordCount(),
                candidate.suggestedMaximumWordCount(), candidate.reasons(), candidate.eventWeights(),
                candidate.compressibleItems(), candidate.nonCompressibleCausalNodes(), candidate.splitSuggestions(),
                candidate.availableActions(), "model", null,
                ChapterCapacityModels.RESULT_REQUIRES_LONG_CONTEXT.equals(candidate.status()));
    }

    public boolean requiresLongContext(Long assessmentId, Integer providerContextTokens) {
        if (providerContextTokens == null) {
            return false;
        }
        ChapterCapacityAssessmentEntity assessment = requireAssessment(assessmentId);
        int estimatedContextTokens = Math.max(1, semanticSource(assessmentId).length() / 2)
                + assessment.getTargetWordCount();
        return estimatedContextTokens > providerContextTokens * 0.8D;
    }

    public CapacityResult longContextFallback(Long assessmentId) {
        CapacityResult fallback = result(requireAssessment(assessmentId));
        return new CapacityResult(ChapterCapacityModels.RESULT_REQUIRES_LONG_CONTEXT,
                fallback.suggestedMinimumWordCount(),
                fallback.suggestedMaximumWordCount(), append(fallback.reasons(), "当前 Provider 上下文窗口不足"),
                fallback.eventWeights(), fallback.compressibleItems(), fallback.nonCompressibleCausalNodes(),
                fallback.splitSuggestions(), List.of("switch_provider", "reduce_context", "plan_issue_112"),
                "fallback", "provider_context_insufficient", true);
    }

    public void complete(Long assessmentId, CapacityResult result, Long modelCallId) {
        ChapterCapacityAssessmentEntity assessment = requireAssessment(assessmentId);
        assessmentMapper.update(null, new UpdateWrapper<ChapterCapacityAssessmentEntity>()
                .eq("id", assessmentId).eq("version", assessment.getVersion())
                .in("assessment_status", STATUS_QUEUED, STATUS_RUNNING, STATUS_FAILED)
                .set("assessment_status", STATUS_READY).set("result_json", json(result))
                .set("model_call_id", modelCallId).set("error_code", null).set("error_message", null)
                .setSql("version = version + 1").set("gmt_modified", LocalDateTime.now()));
    }

    public void fail(Long assessmentId, Exception exception) {
        ChapterCapacityAssessmentEntity assessment = requireAssessment(assessmentId);
        assessmentMapper.update(null, new UpdateWrapper<ChapterCapacityAssessmentEntity>()
                .eq("id", assessmentId).eq("version", assessment.getVersion())
                .in("assessment_status", STATUS_QUEUED, STATUS_RUNNING)
                .set("assessment_status", STATUS_FAILED).set("error_code", "CAPACITY_ASSESSMENT_FAILED")
                .set("error_message", safeMessage(exception)).setSql("version = version + 1")
                .set("gmt_modified", LocalDateTime.now()));
    }

    private void validateCreateRequest(CreateAssessmentRequest request) {
        if (request == null || !StringUtils.hasText(request.idempotencyKey())
                || request.idempotencyKey().length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "容量评估必须提供长度不超过 128 的 idempotencyKey");
        }
        try {
            lengthPolicy.resolveTargetWordCount(request.lengthPreset(), request.customWordCount());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private ChapterCapacityAssessmentEntity requireAssessment(Long assessmentId) {
        ChapterCapacityAssessmentEntity assessment = assessmentId == null
                ? null : assessmentMapper.selectById(assessmentId);
        if (assessment == null || Integer.valueOf(1).equals(assessment.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_CAPACITY_ASSESSMENT_NOT_FOUND, "容量评估不存在");
        }
        return assessment;
    }

    private ChapterCapacityAssessmentEntity findByIdempotency(Long chapterId, String key) {
        return assessmentMapper.selectOne(new LambdaQueryWrapper<ChapterCapacityAssessmentEntity>()
                .eq(ChapterCapacityAssessmentEntity::getChapterId, chapterId)
                .eq(ChapterCapacityAssessmentEntity::getIdempotencyKey, key)
                .eq(ChapterCapacityAssessmentEntity::getDeleted, 0));
    }

    private AiTaskEntity createTask(ChapterEntity chapter, ChapterPlanView plan, CompiledCapacity compiled) {
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(WORKFLOW_TYPE);
        task.setTaskStatus(STATUS_QUEUED);
        task.setWorkId(chapter.getWorkId());
        task.setChapterId(chapter.getId());
        task.setTaskInputJson(json(Map.of(
                "chapterPlanVersionId", plan.id(),
                "inputFingerprint", compiled.fingerprint())));
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);
        return task;
    }

    private CapacityAssessmentView view(ChapterCapacityAssessmentEntity entity) {
        return new CapacityAssessmentView(entity.getId(), entity.getWorkId(), entity.getChapterId(),
                entity.getChapterPlanVersionId(), entity.getScenePlanNo(), entity.getTargetWordCount(),
                entity.getAssessmentStatus(), result(entity), entity.getBriefTemplateVersion(),
                entity.getBriefFingerprint(), entity.getInputFingerprint(), entity.getAiTaskId(),
                entity.getAgentRunId(), entity.getModelCallId(), entity.getErrorCode(), entity.getErrorMessage(),
                entity.getVersion(), entity.getGmtCreate(), entity.getGmtModified());
    }

    private CapacityResult result(ChapterCapacityAssessmentEntity entity) {
        try {
            return objectMapper.readValue(entity.getResultJson(), CapacityResult.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "容量评估结果无法读取", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "容量评估数据无法序列化", exception);
        }
    }

    private List<String> append(List<String> values, String value) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(values == null ? List.of() : values);
        result.add(value);
        return List.copyOf(result);
    }

    private String safeMessage(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        if (!StringUtils.hasText(message)) {
            return "容量评估执行失败";
        }
        return message.length() > 512 ? message.substring(0, 512) : message;
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CHAPTER_CAPACITY_STATE_CONFLICT, message);
    }
}
