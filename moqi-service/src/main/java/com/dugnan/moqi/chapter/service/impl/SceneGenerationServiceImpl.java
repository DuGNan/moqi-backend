package com.dugnan.moqi.chapter.service.impl;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
import com.dugnan.moqi.agent.entity.AgentRunStepEntity;
import com.dugnan.moqi.agent.mapper.AgentRunStepMapper;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityAssessmentService;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.CreateSceneGenerationRequest;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.GenerationSceneList;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.GenerationSceneView;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.RetrySceneRequest;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.SceneGenerationCreated;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.service.SceneGenerationService;
import com.dugnan.moqi.chapter.stream.SceneGenerationEvent;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;
import com.dugnan.moqi.planning.PublishedScenePlanQueryPort;
import com.dugnan.moqi.sourcechain.SourcePropagationService;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 实现场景级候选正文生成批次的创建、查询、取消和步骤重试。
 */
@Service
public class SceneGenerationServiceImpl implements SceneGenerationService {

    private static final String LOCAL_USER = "local-user";
    private static final String WORKFLOW_TYPE = "scene_novel_generation";
    private static final String TASK_TYPE = "scene_novel_generation";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_PREVIEW = "preview";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELED = "canceled";
    private static final String SCENE_PENDING = "pending";
    private static final String SCENE_COPIED = "copied";
    private static final String PROMPT_TEMPLATE_VERSION = "scene-novel-v4";
    private static final Set<String> SELECTION_MODES = Set.of("all", "continue_from", "rewrite_selected");

    private final ChapterMapper chapterMapper;
    private final ChapterGenerationMapper generationMapper;
    private final ChapterGenerationSceneMapper sceneMapper;
    private final AiTaskMapper taskMapper;
    private final AgentRunStepMapper agentRunStepMapper;
    private final PublishedScenePlanQueryPort scenePlanQueryPort;
    private final UserConfigService userConfigService;
    private final AgentRuntime agentRuntime;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ChapterGenerationLengthPolicy lengthPolicy;
    private final ChapterGenerationBriefService briefService;
    private final ChapterCapacityAssessmentService capacityAssessmentService;
    private SourcePropagationService sourcePropagationService = SourcePropagationService.noop();

    @Autowired
    public void setSourcePropagationService(SourcePropagationService sourcePropagationService) {
        this.sourcePropagationService = sourcePropagationService;
    }

    public SceneGenerationServiceImpl(
            ChapterMapper chapterMapper,
            ChapterGenerationMapper generationMapper,
            ChapterGenerationSceneMapper sceneMapper,
            AiTaskMapper taskMapper,
            AgentRunStepMapper agentRunStepMapper,
            PublishedScenePlanQueryPort scenePlanQueryPort,
            UserConfigService userConfigService,
            AgentRuntime agentRuntime,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            ChapterGenerationLengthPolicy lengthPolicy,
            ChapterGenerationBriefService briefService,
            ChapterCapacityAssessmentService capacityAssessmentService) {
        this.chapterMapper = chapterMapper;
        this.generationMapper = generationMapper;
        this.sceneMapper = sceneMapper;
        this.taskMapper = taskMapper;
        this.agentRunStepMapper = agentRunStepMapper;
        this.scenePlanQueryPort = scenePlanQueryPort;
        this.userConfigService = userConfigService;
        this.agentRuntime = agentRuntime;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.lengthPolicy = lengthPolicy;
        this.briefService = briefService;
        this.capacityAssessmentService = capacityAssessmentService;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public SceneGenerationCreated create(Long chapterId, CreateSceneGenerationRequest request) {
        ChapterEntity chapter = requireChapter(chapterId);
        validateRequest(request);
        ChapterGenerationEntity existing = findByIdempotency(chapterId, request.idempotencyKey());
        if (existing != null) {
            return created(existing);
        }
        ChapterPlanView chapterPlan = request.scenePlanNo() == null
                ? scenePlanQueryPort.loadCurrent(chapterId)
                : scenePlanQueryPort.loadPublished(chapterId, request.scenePlanNo());
        ChapterGenerationBrief generationBrief = briefService.compile(chapterId, chapterPlan);
        List<ScenePlanView> plannedScenes = chapterPlan.scenes().stream()
                .filter(scene -> "planned".equals(scene.content().status()))
                .sorted(Comparator.comparing(ScenePlanView::sequence))
                .toList();
        if (plannedScenes.isEmpty()) {
            throw new BusinessException(ErrorCode.GENERATION_SELECTION_INVALID, "章节没有可生成的已发布场景");
        }
        String lengthPreset = lengthPolicy.normalizePreset(request.lengthPreset());
        int targetWordCount = lengthPolicy.resolveTargetWordCount(lengthPreset, request.customWordCount());
        Map<String, Object> capacitySnapshot = capacityAssessmentService.resolveForGeneration(
                chapterPlan, generationBrief, targetWordCount,
                request.capacityAssessmentId(), request.capacityDecision());
        LlmExecutionConfig executionConfig = userConfigService.requireAvailableExecutionConfig();
        AiTaskEntity task = createTask(chapter);
        ChapterGenerationEntity generation = createGeneration(
                chapter, chapterPlan, request, task, executionConfig, generationBrief,
                lengthPreset, targetWordCount, capacitySnapshot);
        generationMapper.insert(generation);
        sourcePropagationService.generationCreated(chapterId, generation.getId());

        ChapterGenerationEntity baseGeneration = requireBaseGeneration(
                request.baseGenerationId(), chapter, chapterPlan, request);
        Map<Long, ChapterGenerationSceneEntity> baseScenes = baseGeneration == null
                ? Map.of() : baseScenes(baseGeneration.getId());
        Set<Long> selectedSceneIds = selectedSceneIds(plannedScenes, request);
        saveScenes(generation, plannedScenes, selectedSceneIds, baseScenes);

        AgentRunView run = agentRuntime.start(new StartAgentRunCommand(
                LOCAL_USER,
                chapter.getWorkId(),
                chapter.getId(),
                WORKFLOW_TYPE,
                request.idempotencyKey(),
                chapterPlan.planNo().longValue(),
                runInput(generation.getId(), request, targetWordCount),
                task.getId()));
        int generationUpdated = generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("id", generation.getId()).eq("version", generation.getVersion())
                .set("agent_run_id", run.runId()).set("version", generation.getVersion() + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (generationUpdated != 1) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "生成批次关联运行任务时发生并发冲突");
        }
        generation.setAgentRunId(run.runId());
        generation.setVersion(generation.getVersion() + 1);
        int taskUpdated = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId()).eq("version", task.getVersion())
                .set("result_generation_id", generation.getId()).set("version", task.getVersion() + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (taskUpdated != 1) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "AI 任务关联生成批次时发生并发冲突");
        }
        task.setResultGenerationId(generation.getId());
        task.setVersion(task.getVersion() + 1);
        eventPublisher.publishEvent(SceneGenerationEvent.generation(
                "generation.started", chapterId, generation.getId(), STATUS_QUEUED));
        return created(requireGeneration(generation.getId()));
    }

    @Override
    public SceneGenerationCreated regenerate(Long generationId, CreateSceneGenerationRequest request) {
        ChapterGenerationEntity source = requireGeneration(generationId);
        if (!Set.of("continue_from", "rewrite_selected").contains(normalizedMode(request.selectionMode()))) {
            throw invalidSelection("重新生成必须使用 continue_from 或 rewrite_selected 选择模式");
        }
        if (request.baseGenerationId() != null && !generationId.equals(request.baseGenerationId())) {
            throw invalidSelection("重新生成的 baseGenerationId 必须与路径中的 generationId 一致");
        }
        return create(source.getChapterId(), new CreateSceneGenerationRequest(
                request.scenePlanNo(), request.selectionMode(), request.fromSceneKey(), request.sceneKeys(),
                generationId, request.idempotencyKey(), request.lengthPreset(), request.customWordCount(),
                request.temperature(), request.capacityAssessmentId(), request.capacityDecision()));
    }

    @Override
    public GenerationSceneList listScenes(Long generationId) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        return new GenerationSceneList(generationId, sceneMapper.selectList(
                new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                .eq(ChapterGenerationSceneEntity::getGenerationId, generationId)
                .eq(ChapterGenerationSceneEntity::getDeleted, 0)
                .orderByAsc(ChapterGenerationSceneEntity::getSequenceNo))
                .stream().map(scene -> view(scene, generation.getAgentRunId())).toList());
    }

    @Override
    public GenerationSceneView getScene(Long generationId, Long sceneId) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        ChapterGenerationSceneEntity scene = sceneId == null ? null : sceneMapper.selectById(sceneId);
        if (scene == null || Integer.valueOf(1).equals(scene.getDeleted())
                || !generationId.equals(scene.getGenerationId())) {
            throw new BusinessException(ErrorCode.GENERATION_SCENE_NOT_FOUND, "场景候选不存在");
        }
        return view(scene, generation.getAgentRunId());
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView cancel(Long generationId) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        if (generation.getAgentRunId() == null) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "生成批次未关联运行任务");
        }
        AgentRunView run = agentRuntime.cancel(generation.getAgentRunId());
        generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("id", generationId).eq("version", generation.getVersion())
                .in("generation_status", Set.of(STATUS_QUEUED, STATUS_RUNNING))
                .set("generation_status", STATUS_CANCELED).set("version", generation.getVersion() + 1)
                .set("gmt_modified", LocalDateTime.now()));
        sceneMapper.update(null, new UpdateWrapper<ChapterGenerationSceneEntity>()
                .eq("generation_id", generationId).eq("deleted", 0)
                .in("scene_status", Set.of(SCENE_PENDING, STATUS_RUNNING))
                .set("scene_status", STATUS_CANCELED).setSql("version = version + 1")
                .set("gmt_modified", LocalDateTime.now()));
        eventPublisher.publishEvent(SceneGenerationEvent.generation(
                "generation.canceled", generation.getChapterId(), generationId, STATUS_CANCELED));
        return run;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView retryScene(Long generationId, Long sceneId, RetrySceneRequest request) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        GenerationSceneView scene = getScene(generationId, sceneId);
        if (!STATUS_FAILED.equals(scene.sceneStatus()) || !Boolean.TRUE.equals(scene.retryable())
                || generation.getAgentRunId() == null
                || request == null || request.expectedAttempt() == null) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "当前场景不能重试");
        }
        AgentRunView run = agentRuntime.retryStep(new RetryAgentStepCommand(
                generation.getAgentRunId(), "generate_scene:" + scene.sceneKey(), request.expectedAttempt()));
        int sceneUpdated = sceneMapper.update(null, new UpdateWrapper<ChapterGenerationSceneEntity>()
                .eq("id", sceneId).eq("version", version(scene))
                .eq("scene_status", STATUS_FAILED).set("scene_status", SCENE_PENDING)
                .set("version", version(scene) + 1).set("gmt_modified", LocalDateTime.now()));
        if (sceneUpdated != 1) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "场景状态已变化，请刷新后重试");
        }
        generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("id", generationId).set("generation_status", STATUS_RUNNING).setSql("version = version + 1")
                .set("gmt_modified", LocalDateTime.now()));
        return run;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView retryCohesion(Long generationId) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        if (!STATUS_FAILED.equals(generation.getGenerationStatus())
                || !"cohesive_chapter".equals(generation.getContentAssemblyMode())
                || !"failed".equals(generation.getCohesionStatus())
                || generation.getAgentRunId() == null) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "当前整章收束不能重试");
        }
        AgentRunStepEntity step = latestStep(generation.getAgentRunId(), "cohere_chapter");
        if (step == null || !STATUS_FAILED.equals(step.getStepStatus())
                || !Integer.valueOf(1).equals(step.getRetryable())) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "当前整章收束不能重试");
        }
        AgentRunView run = agentRuntime.retryStep(new RetryAgentStepCommand(
                generation.getAgentRunId(), "cohere_chapter", step.getAttempt()));
        generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("id", generationId).eq("generation_status", STATUS_FAILED)
                .eq("cohesion_status", "failed")
                .set("generation_status", STATUS_RUNNING).set("cohesion_status", "pending")
                .set("generated_content", null).set("word_count", 0)
                .setSql("version = version + 1").set("gmt_modified", LocalDateTime.now()));
        return run;
    }

    private ChapterGenerationEntity createGeneration(
            ChapterEntity chapter,
            ChapterPlanView plan,
            CreateSceneGenerationRequest request,
            AiTaskEntity task,
            LlmExecutionConfig executionConfig,
            ChapterGenerationBrief generationBrief,
            String lengthPreset,
            int targetWordCount,
            Map<String, Object> capacitySnapshot) {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setWorkId(chapter.getWorkId());
        generation.setChapterId(chapter.getId());
        generation.setChapterPlanVersionId(plan.id());
        generation.setOutlineId(plan.outlineId());
        generation.setOutlineRevision(plan.outlineRevision());
        generation.setGenerationStatus(STATUS_QUEUED);
        generation.setGenerationMode("scene_novel_generation");
        generation.setContentAssemblyMode("cohesive_chapter");
        generation.setCohesionStatus("pending");
        generation.setCohesionTemplateVersion("chapter-cohesion-v1");
        generation.setSelectionMode(normalizedMode(request.selectionMode()));
        generation.setIdempotencyKey(request.idempotencyKey());
        generation.setLengthPreset(lengthPreset);
        generation.setCustomWordCount("custom".equals(lengthPreset) ? request.customWordCount() : null);
        Map<String, Object> basisSnapshot = new LinkedHashMap<>();
        basisSnapshot.put("chapterPlanVersionId", plan.id());
        basisSnapshot.put("chapterPlanNo", plan.planNo());
        basisSnapshot.put("outlineId", plan.outlineId());
        basisSnapshot.put("outlineRevision", plan.outlineRevision());
        basisSnapshot.put("lengthPreset", lengthPreset);
        basisSnapshot.put("targetChapterWordCount", targetWordCount);
        basisSnapshot.put("temperature", request.temperature());
        basisSnapshot.put("chapterGenerationBrief", briefSnapshot(generationBrief));
        basisSnapshot.put("chapterCapacityAssessment", capacitySnapshot);
        generation.setBasisSnapshotJson(json(basisSnapshot));
        generation.setExecutionConfigJson(json(executionConfig.descriptor()));
        generation.setWordCount(0);
        generation.setAiTaskId(task.getId());
        generation.setValidityStatus("current");
        generation.setDeleted(0);
        generation.setVersion(0);
        return generation;
    }

    private Map<String, Object> briefSnapshot(ChapterGenerationBrief brief) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", brief.schemaVersion());
        snapshot.put("templateVersion", brief.templateVersion());
        snapshot.put("workId", brief.workId());
        snapshot.put("workTitle", brief.workTitle());
        snapshot.put("chapterId", brief.chapterId());
        snapshot.put("chapterNo", brief.chapterNo());
        snapshot.put("chapterTitle", brief.chapterTitle());
        snapshot.put("chapterPurpose", brief.chapterPurpose());
        snapshot.put("chapterGoal", brief.chapterGoal());
        snapshot.put("coreConflict", brief.coreConflict());
        snapshot.put("openingConditions", brief.openingConditions());
        snapshot.put("readerKnowledge", brief.readerKnowledge());
        snapshot.put("eventCausality", brief.eventCausality());
        snapshot.put("stateChanges", brief.stateChanges());
        snapshot.put("characterConstraints", brief.characterConstraints());
        snapshot.put("entityExplanations", brief.entityExplanations());
        snapshot.put("requiredEndingState", brief.requiredEndingState());
        snapshot.put("creativeFreedom", brief.creativeFreedom());
        snapshot.put("prohibitedInventions", brief.prohibitedInventions());
        snapshot.put("sourceRefs", brief.sourceRefs());
        snapshot.put("fingerprint", brief.fingerprint());
        snapshot.put("compiledAt", brief.compiledAt().toString());
        snapshot.put("content", brief.content());
        return snapshot;
    }

    private void saveScenes(
            ChapterGenerationEntity generation,
            List<ScenePlanView> plannedScenes,
            Set<Long> selectedSceneIds,
            Map<Long, ChapterGenerationSceneEntity> baseScenes) {
        for (ScenePlanView planScene : plannedScenes) {
            ChapterGenerationSceneEntity scene = new ChapterGenerationSceneEntity();
            scene.setGenerationId(generation.getId());
            scene.setScenePlanVersionId(planScene.scenePlanId());
            scene.setSceneKey(planScene.sceneKey());
            scene.setSequenceNo(planScene.sequence());
            scene.setPromptTemplateVersion(PROMPT_TEMPLATE_VERSION);
            scene.setDeleted(0);
            scene.setVersion(0);
            if (selectedSceneIds.contains(planScene.scenePlanId())) {
                scene.setSceneStatus(SCENE_PENDING);
                scene.setWordCount(0);
            } else {
                ChapterGenerationSceneEntity source = baseScenes.get(planScene.scenePlanId());
                if (source == null || !("completed".equals(source.getSceneStatus())
                        || SCENE_COPIED.equals(source.getSceneStatus()))) {
                    throw new BusinessException(ErrorCode.GENERATION_SELECTION_INVALID, "基础批次缺少可复用的场景候选");
                }
                scene.setSceneStatus(SCENE_COPIED);
                scene.setGeneratedContent(source.getGeneratedContent());
                scene.setContentHash(source.getContentHash());
                scene.setWordCount(source.getWordCount());
                scene.setSourceSceneDraftId(source.getId());
                scene.setContextSnapshotId(source.getContextSnapshotId());
                scene.setFinishReason(source.getFinishReason());
                scene.setInputTokens(source.getInputTokens());
                scene.setOutputTokens(source.getOutputTokens());
                scene.setTotalTokens(source.getTotalTokens());
                scene.setElapsedMillis(source.getElapsedMillis());
            }
            sceneMapper.insert(scene);
        }
    }

    private Set<Long> selectedSceneIds(List<ScenePlanView> scenes, CreateSceneGenerationRequest request) {
        String mode = normalizedMode(request.selectionMode());
        if ("all".equals(mode)) {
            return scenes.stream().map(ScenePlanView::scenePlanId).collect(Collectors.toSet());
        }
        if ("continue_from".equals(mode)) {
            ScenePlanView first = scenes.stream().filter(scene -> scene.sceneKey().equals(request.fromSceneKey()))
                    .findFirst().orElseThrow(() -> invalidSelection("fromSceneKey 不属于当前场景计划"));
            return scenes.stream().filter(scene -> scene.sequence() >= first.sequence())
                    .map(ScenePlanView::scenePlanId).collect(Collectors.toSet());
        }
        Set<String> sceneKeys = Set.copyOf(request.sceneKeys());
        if (sceneKeys.isEmpty() || scenes.stream().anyMatch(scene -> !sceneKeys.contains(scene.sceneKey()))) {
            throw invalidSelection("sceneKeys 必须是当前场景计划中的非空子集");
        }
        return scenes.stream().filter(scene -> sceneKeys.contains(scene.sceneKey()))
                .map(ScenePlanView::scenePlanId).collect(Collectors.toSet());
    }

    private ChapterGenerationEntity requireBaseGeneration(
            Long baseGenerationId,
            ChapterEntity chapter,
            ChapterPlanView plan,
            CreateSceneGenerationRequest request) {
        if ("all".equals(normalizedMode(request.selectionMode()))) {
            if (baseGenerationId != null) {
                throw invalidSelection("全量生成不接受 baseGenerationId");
            }
            return null;
        }
        ChapterGenerationEntity generation = requireGeneration(baseGenerationId);
        if (!chapter.getId().equals(generation.getChapterId())
                || !plan.id().equals(generation.getChapterPlanVersionId())) {
            throw invalidSelection("基础生成批次不属于当前章节规划版本");
        }
        return generation;
    }

    private Map<Long, ChapterGenerationSceneEntity> baseScenes(Long generationId) {
        return sceneMapper.selectList(new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                .eq(ChapterGenerationSceneEntity::getGenerationId, generationId)
                .eq(ChapterGenerationSceneEntity::getDeleted, 0)).stream()
                .collect(Collectors.toMap(ChapterGenerationSceneEntity::getScenePlanVersionId, item -> item));
    }

    private AiTaskEntity createTask(ChapterEntity chapter) {
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(TASK_TYPE);
        task.setTaskStatus(STATUS_QUEUED);
        task.setWorkId(chapter.getWorkId());
        task.setChapterId(chapter.getId());
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);
        return task;
    }

    private SceneGenerationCreated created(ChapterGenerationEntity generation) {
        return new SceneGenerationCreated(generation.getId(), generation.getAiTaskId(), generation.getAgentRunId(),
                generation.getChapterPlanVersionId(), generation.getGenerationStatus(), generation.getGmtCreate());
    }

    private GenerationSceneView view(ChapterGenerationSceneEntity entity, Long agentRunId) {
        AgentRunStepEntity step = latestSceneStep(agentRunId, entity.getSceneKey());
        boolean retryable = STATUS_FAILED.equals(entity.getSceneStatus()) && step != null
                && STATUS_FAILED.equals(step.getStepStatus()) && Integer.valueOf(1).equals(step.getRetryable());
        return new GenerationSceneView(entity.getId(), entity.getGenerationId(), entity.getScenePlanVersionId(),
                entity.getSceneKey(), entity.getSequenceNo(), entity.getSceneStatus(), entity.getGeneratedContent(),
                entity.getContextSnapshotId(),
                entity.getPromptTemplateVersion(), entity.getWordCount(), entity.getSourceSceneDraftId(),
                entity.getModelCallId(), entity.getFinishReason(), entity.getInputTokens(), entity.getOutputTokens(),
                entity.getTotalTokens(), entity.getElapsedMillis(), step == null ? null : step.getAttempt(), retryable,
                safeErrorCode(step), safeErrorMessage(step), entity.getGmtModified());
    }

    private AgentRunStepEntity latestSceneStep(Long agentRunId, String sceneKey) {
        if (agentRunId == null || !StringUtils.hasText(sceneKey)) {
            return null;
        }
        return latestStep(agentRunId, "generate_scene:" + sceneKey);
    }

    private AgentRunStepEntity latestStep(Long agentRunId, String stepKey) {
        return agentRunStepMapper.selectOne(new LambdaQueryWrapper<AgentRunStepEntity>()
                .eq(AgentRunStepEntity::getRunId, agentRunId)
                .eq(AgentRunStepEntity::getStepKey, stepKey)
                .eq(AgentRunStepEntity::getDeleted, 0)
                .orderByDesc(AgentRunStepEntity::getAttempt).last("LIMIT 1"));
    }

    private String safeErrorCode(AgentRunStepEntity step) {
        return step == null || !STATUS_FAILED.equals(step.getStepStatus()) ? null : step.getErrorCode();
    }

    private String safeErrorMessage(AgentRunStepEntity step) {
        return step == null || !STATUS_FAILED.equals(step.getStepStatus()) ? null : step.getErrorMessage();
    }

    private int version(GenerationSceneView scene) {
        ChapterGenerationSceneEntity entity = sceneMapper.selectById(scene.id());
        return entity == null || entity.getVersion() == null ? 0 : entity.getVersion();
    }

    private ChapterGenerationEntity findByIdempotency(Long chapterId, String idempotencyKey) {
        return generationMapper.selectOne(new LambdaQueryWrapper<ChapterGenerationEntity>()
                .eq(ChapterGenerationEntity::getChapterId, chapterId)
                .eq(ChapterGenerationEntity::getIdempotencyKey, idempotencyKey)
                .eq(ChapterGenerationEntity::getDeleted, 0));
    }

    private ChapterGenerationEntity requireGeneration(Long generationId) {
        ChapterGenerationEntity generation = generationId == null ? null : generationMapper.selectById(generationId);
        if (generation == null || Integer.valueOf(1).equals(generation.getDeleted())) {
            throw new BusinessException(ErrorCode.GENERATION_NOT_FOUND, "生成批次不存在");
        }
        return generation;
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private void validateRequest(CreateSceneGenerationRequest request) {
        if (request == null || !StringUtils.hasText(request.idempotencyKey())
                || request.idempotencyKey().length() > 128) {
            throw invalidSelection("idempotencyKey 不能为空且长度不能超过 128");
        }
        if (!SELECTION_MODES.contains(normalizedMode(request.selectionMode()))) {
            throw invalidSelection("selectionMode 不合法");
        }
        try {
            lengthPolicy.resolveTargetWordCount(
                    request.lengthPreset(), request.customWordCount());
        } catch (IllegalArgumentException exception) {
            throw invalidSelection(exception.getMessage());
        }
    }

    private String normalizedMode(String value) {
        return StringUtils.hasText(value) ? value.trim() : "all";
    }

    private BusinessException invalidSelection(String message) {
        return new BusinessException(ErrorCode.GENERATION_SELECTION_INVALID, message);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成配置无法序列化", exception);
        }
    }

    private Map<String, Object> runInput(
            Long generationId,
            CreateSceneGenerationRequest request,
            int targetWordCount) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("generationId", generationId);
        input.put("targetChapterWordCount", targetWordCount);
        if (request.temperature() != null) {
            input.put("temperature", request.temperature());
        }
        return input;
    }
}
