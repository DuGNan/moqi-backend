package com.dugnan.moqi.chapter.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;
import com.dugnan.moqi.chapter.stream.SceneGenerationEvent;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.SceneGenerationContextFocus;
import com.dugnan.moqi.context.StoryContextBuildCommand;
import com.dugnan.moqi.context.StoryContextEngine;
import com.dugnan.moqi.context.StoryContextProfile;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextSnapshotQueryPort;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmExecutionConfigDescriptor;
import com.dugnan.moqi.llm.LlmCallContext;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmResponseMetadata;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 使用 Agent Runtime 按场景生成可恢复小说候选正文。
 */
@Component
public class SceneNovelGenerationWorkflowDefinition implements AgentWorkflowDefinition {

    public static final String WORKFLOW_TYPE = "scene_novel_generation";
    private static final String LOAD = "load_generation";
    private static final String GENERATE_PREFIX = "generate_scene:";
    private static final String FINALIZE = "finalize_generation";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_PREVIEW = "preview";
    private static final String SCENE_PENDING = "pending";
    private static final String SCENE_RUNNING = "running";
    private static final String SCENE_COMPLETED = "completed";
    private static final String SCENE_COPIED = "copied";
    private static final String TEMPLATE_VERSION = "scene-novel-v1";
    private static final int MAX_ATTEMPTS = 3;

    private final ChapterGenerationMapper generationMapper;
    private final ChapterGenerationSceneMapper generationSceneMapper;
    private final ScenePlanVersionMapper scenePlanMapper;
    private final StoryContextEngine contextEngine;
    private final StoryContextSnapshotQueryPort snapshotQueryPort;
    private final UserConfigService userConfigService;
    private final LlmProviderFactory providerFactory;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public SceneNovelGenerationWorkflowDefinition(
            ChapterGenerationMapper generationMapper,
            ChapterGenerationSceneMapper generationSceneMapper,
            ScenePlanVersionMapper scenePlanMapper,
            StoryContextEngine contextEngine,
            StoryContextSnapshotQueryPort snapshotQueryPort,
            UserConfigService userConfigService,
            LlmProviderFactory providerFactory,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this.generationMapper = generationMapper;
        this.generationSceneMapper = generationSceneMapper;
        this.scenePlanMapper = scenePlanMapper;
        this.contextEngine = contextEngine;
        this.snapshotQueryPort = snapshotQueryPort;
        this.userConfigService = userConfigService;
        this.providerFactory = providerFactory;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String workflowType() {
        return WORKFLOW_TYPE;
    }

    @Override
    public String startStepKey() {
        return LOAD;
    }

    @Override
    public Duration timeout() {
        return Duration.ofHours(2);
    }

    @Override
    public int maxAttempts(String stepKey) {
        return MAX_ATTEMPTS;
    }

    @Override
    public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) {
        Long generationId = generationId(context);
        if (LOAD.equals(stepKey)) {
            ChapterGenerationEntity generation = requireGeneration(generationId);
            return AgentStepResult.completed(Map.of("generationId", generation.getId()), Map.of(), nextStep(generationId, 0));
        }
        if (FINALIZE.equals(stepKey)) {
            return AgentStepResult.completed(Map.of("generationId", generationId), Map.of(), null);
        }
        if (!stepKey.startsWith(GENERATE_PREFIX)) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "场景生成步骤键不合法");
        }
        return generate(stepKey.substring(GENERATE_PREFIX.length()), context, generationId);
    }

    @Override
    public void applyResult(String stepKey, AgentStepExecutionContext context, AgentStepResult result) {
        Long generationId = generationId(context);
        if (LOAD.equals(stepKey)) {
            generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                    .eq("id", generationId).eq("generation_status", STATUS_QUEUED)
                    .set("generation_status", STATUS_RUNNING).setSql("version = version + 1")
                    .set("gmt_modified", LocalDateTime.now()));
            ChapterGenerationEntity generation = requireGeneration(generationId);
            eventPublisher.publishEvent(SceneGenerationEvent.generation(
                    "generation.started", generation.getChapterId(), generationId, STATUS_RUNNING));
            return;
        }
        if (FINALIZE.equals(stepKey)) {
            finalizeGeneration(generationId);
            return;
        }
        applySceneResult(context, result, generationId);
    }

    private AgentStepResult generate(String sceneKey, AgentStepExecutionContext context, Long generationId) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        ChapterGenerationSceneEntity scene = requireScene(generationId, sceneKey);
        if (SCENE_COMPLETED.equals(scene.getSceneStatus()) || SCENE_COPIED.equals(scene.getSceneStatus())) {
            return AgentStepResult.completed(Map.of("sceneId", scene.getId(), "skipped", true), Map.of(),
                    nextStep(generationId, scene.getSequenceNo()));
        }
        LlmExecutionConfig executionConfig = verifyExecutionConfig(generation, context);
        LlmProvider provider = providerFactory.create(executionConfig.runtimeConfig());
        StoryContextSnapshot snapshot = contextSnapshot(generation, scene, provider);
        provider = providerFactory.createObserved(
                executionConfig,
                LlmCallContext.builder(WORKFLOW_TYPE, "generate_scene")
                        .workId(generation.getWorkId())
                        .chapterId(generation.getChapterId())
                        .generationSceneId(scene.getId())
                        .agentRunId(context.runId())
                        .agentStepId(context.stepId())
                        .logicalCallId("agent-step:" + context.stepId() + ":scene")
                        .promptTemplateVersion(TEMPLATE_VERSION)
                        .sourceFingerprint(snapshot.contentHash())
                        .build());
        StringBuilder content = new StringBuilder();
        long started = System.nanoTime();
        LlmStreamCall call = null;
        try {
            eventPublisher.publishEvent(SceneGenerationEvent.scene("generation.scene.started", generation.getChapterId(),
                    generationId, scene.getId(), scene.getSceneKey(), SCENE_RUNNING));
            call = provider.stream(
                    new com.dugnan.moqi.llm.LlmRequest(snapshot.toMessages(), new LlmOptions(
                            maxOutputTokens(context.input()), temperature(context.input()), List.of(), LlmResponseFormat.TEXT)),
                    event -> consumeDelta(event, context, generation, scene, content));
            context.callRegistry().register(context.runId(), call);
            LlmStreamResult streamResult = call.await();
            if (streamResult.status() == LlmStreamStatus.CANCELED) {
                throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "场景生成已取消");
            }
            if (streamResult.status() == LlmStreamStatus.FAILED) {
                throw new LlmProviderException(streamResult.error());
            }
            if (!StringUtils.hasText(content)) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "模型未返回场景正文");
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("sceneId", scene.getId());
            output.put("contextSnapshotId", snapshot.id());
            Long modelCallId = streamResult.metadata() == null ? null : streamResult.metadata().modelCallId();
            output.put("modelCallId", modelCallId);
            output.put("content", content.toString());
            output.put("elapsedMillis", Duration.ofNanos(System.nanoTime() - started).toMillis());
            putMetadata(output, streamResult.metadata());
            return new AgentStepResult(output, Map.of("lastSceneId", scene.getId()),
                    nextStep(generationId, scene.getSequenceNo()),
                    modelCallId == null ? null : String.valueOf(modelCallId),
                    null);
        } finally {
            context.callRegistry().unregister(context.runId(), call);
        }
    }

    private StoryContextSnapshot contextSnapshot(
            ChapterGenerationEntity generation,
            ChapterGenerationSceneEntity scene,
            LlmProvider provider) {
        if (scene.getContextSnapshotId() != null) {
            return snapshotQueryPort.load(scene.getContextSnapshotId());
        }
        ScenePlanVersionEntity plan = scenePlanMapper.selectById(scene.getScenePlanVersionId());
        if (plan == null || Integer.valueOf(1).equals(plan.getDeleted())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_NOT_FOUND, "场景规划叶子节点不存在");
        }
        ScenePlanContent planContent = read(plan.getContentJson(), ScenePlanContent.class);
        List<SceneGenerationContextFocus.PreviousSceneDraft> previous = generationSceneMapper.selectList(
                new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                        .eq(ChapterGenerationSceneEntity::getGenerationId, generation.getId())
                        .lt(ChapterGenerationSceneEntity::getSequenceNo, scene.getSequenceNo())
                        .in(ChapterGenerationSceneEntity::getSceneStatus, List.of(SCENE_COMPLETED, SCENE_COPIED))
                        .eq(ChapterGenerationSceneEntity::getDeleted, 0)
                        .orderByAsc(ChapterGenerationSceneEntity::getSequenceNo)).stream()
                .map(item -> new SceneGenerationContextFocus.PreviousSceneDraft(
                        item.getId(), item.getSceneKey(), item.getGeneratedContent()))
                .toList();
        int contextWindow = provider.capabilities().maxContextTokens() == null
                ? 16384 : provider.capabilities().maxContextTokens();
        int reserve = Math.min(StoryContextProfile.SCENE_GENERATION.defaultOutputReserveTokens(), contextWindow / 2);
        StoryContextSnapshot snapshot = contextEngine.build(new StoryContextBuildCommand(
                StoryContextProfile.SCENE_GENERATION,
                generation.getWorkId(),
                generation.getChapterId(),
                null,
                null,
                "请根据已发布场景计划创作本场候选正文。不得改写已确认设定，不得输出分析、标题或隐藏推理。",
                null,
                null,
                contextWindow,
                reserve,
                null,
                new SceneGenerationContextFocus(
                        generation.getChapterPlanVersionId(),
                        plan.getVersion(),
                        plan.getId(),
                        scene.getSceneKey(),
                        json(planContent),
                        previous)));
        int changed = generationSceneMapper.update(null, new UpdateWrapper<ChapterGenerationSceneEntity>()
                .eq("id", scene.getId()).eq("version", scene.getVersion())
                .in("scene_status", List.of(SCENE_PENDING, "failed"))
                .set("context_snapshot_id", snapshot.id()).set("scene_status", SCENE_RUNNING)
                .set("version", scene.getVersion() + 1).set("gmt_modified", LocalDateTime.now()));
        if (changed != 1) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "场景生成状态已变化");
        }
        return snapshot;
    }

    private void applySceneResult(AgentStepExecutionContext context, AgentStepResult result, Long generationId) {
        Long sceneId = longValue(result.outputSummary().get("sceneId"));
        if (Boolean.TRUE.equals(result.outputSummary().get("skipped"))) {
            return;
        }
        ChapterGenerationSceneEntity scene = sceneId == null ? null : generationSceneMapper.selectById(sceneId);
        if (scene == null || !generationId.equals(scene.getGenerationId())) {
            throw new BusinessException(ErrorCode.GENERATION_SCENE_NOT_FOUND, "场景候选不存在");
        }
        if (SCENE_COMPLETED.equals(scene.getSceneStatus())) {
            return;
        }
        int changed = generationSceneMapper.update(null, new UpdateWrapper<ChapterGenerationSceneEntity>()
                .eq("id", sceneId).eq("version", scene.getVersion()).eq("scene_status", SCENE_RUNNING)
                .set("generated_content", result.outputSummary().get("content"))
                .set("content_hash", sha256(String.valueOf(result.outputSummary().get("content"))))
                .set("word_count", wordCount(String.valueOf(result.outputSummary().get("content"))))
                .set("model_call_id", longValue(result.outputSummary().get("modelCallId")))
                .set("finish_reason", stringValue(result.outputSummary().get("finishReason")))
                .set("input_tokens", integerValue(result.outputSummary().get("inputTokens")))
                .set("output_tokens", integerValue(result.outputSummary().get("outputTokens")))
                .set("total_tokens", integerValue(result.outputSummary().get("totalTokens")))
                .set("elapsed_millis", longValue(result.outputSummary().get("elapsedMillis")))
                .set("scene_status", SCENE_COMPLETED).set("version", scene.getVersion() + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (changed != 1) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "场景候选已被并发修改");
        }
        ChapterGenerationEntity generation = requireGeneration(generationId);
        eventPublisher.publishEvent(SceneGenerationEvent.scene("generation.scene.completed", generation.getChapterId(),
                generationId, sceneId, scene.getSceneKey(), SCENE_COMPLETED));
    }

    private void finalizeGeneration(Long generationId) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        List<ChapterGenerationSceneEntity> scenes = generationSceneMapper.selectList(
                new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                        .eq(ChapterGenerationSceneEntity::getGenerationId, generationId)
                        .eq(ChapterGenerationSceneEntity::getDeleted, 0)
                        .orderByAsc(ChapterGenerationSceneEntity::getSequenceNo));
        if (scenes.isEmpty() || scenes.stream().anyMatch(scene -> !(SCENE_COMPLETED.equals(scene.getSceneStatus())
                || SCENE_COPIED.equals(scene.getSceneStatus())))) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "仍有场景候选未完成");
        }
        String content = scenes.stream().map(ChapterGenerationSceneEntity::getGeneratedContent)
                .filter(StringUtils::hasText).collect(Collectors.joining("\n\n"));
        int changed = generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("id", generationId).eq("version", generation.getVersion()).eq("generation_status", STATUS_RUNNING)
                .set("generated_content", content).set("word_count", wordCount(content))
                .set("generation_status", STATUS_PREVIEW).set("version", generation.getVersion() + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (changed != 1) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "生成批次已被并发修改");
        }
        eventPublisher.publishEvent(SceneGenerationEvent.generation(
                "generation.completed", generation.getChapterId(), generationId, STATUS_PREVIEW));
    }

    private void consumeDelta(
            LlmStreamEvent event,
            AgentStepExecutionContext context,
            ChapterGenerationEntity generation,
            ChapterGenerationSceneEntity scene,
            StringBuilder content) {
        if (event instanceof LlmStreamEvent.TextDelta delta && StringUtils.hasText(delta.text())
                && !context.callRegistry().isCancellationRequested(context.runId())) {
            content.append(delta.text());
            eventPublisher.publishEvent(SceneGenerationEvent.delta(generation.getChapterId(), generation.getId(),
                    scene.getId(), scene.getSceneKey(), delta.text()));
        }
    }

    private LlmExecutionConfig verifyExecutionConfig(
            ChapterGenerationEntity generation,
            AgentStepExecutionContext context) {
        LlmExecutionConfigDescriptor expected = read(generation.getExecutionConfigJson(), LlmExecutionConfigDescriptor.class);
        LlmExecutionConfig current = userConfigService.requireAvailableExecutionConfig();
        if (!expected.equals(current.descriptor())) {
            throw new BusinessException(ErrorCode.GENERATION_CONFIG_STALE, "模型配置或凭据已变化，请创建新的生成批次");
        }
        return current;
    }

    private String nextStep(Long generationId, int sequenceNo) {
        ChapterGenerationSceneEntity next = generationSceneMapper.selectList(
                new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                        .eq(ChapterGenerationSceneEntity::getGenerationId, generationId)
                        .gt(ChapterGenerationSceneEntity::getSequenceNo, sequenceNo)
                        .eq(ChapterGenerationSceneEntity::getDeleted, 0)
                        .orderByAsc(ChapterGenerationSceneEntity::getSequenceNo)).stream().findFirst().orElse(null);
        return next == null ? FINALIZE : GENERATE_PREFIX + next.getSceneKey();
    }

    private Long generationId(AgentStepExecutionContext context) {
        Long generationId = longValue(context.input().get("generationId"));
        if (generationId == null) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "场景生成运行缺少 generationId");
        }
        return generationId;
    }

    private ChapterGenerationEntity requireGeneration(Long generationId) {
        ChapterGenerationEntity generation = generationMapper.selectById(generationId);
        if (generation == null || Integer.valueOf(1).equals(generation.getDeleted())) {
            throw new BusinessException(ErrorCode.GENERATION_NOT_FOUND, "生成批次不存在");
        }
        return generation;
    }

    private ChapterGenerationSceneEntity requireScene(Long generationId, String sceneKey) {
        ChapterGenerationSceneEntity scene = generationSceneMapper.selectOne(
                new LambdaQueryWrapper<ChapterGenerationSceneEntity>()
                        .eq(ChapterGenerationSceneEntity::getGenerationId, generationId)
                        .eq(ChapterGenerationSceneEntity::getSceneKey, sceneKey)
                        .eq(ChapterGenerationSceneEntity::getDeleted, 0));
        if (scene == null) {
            throw new BusinessException(ErrorCode.GENERATION_SCENE_NOT_FOUND, "场景候选不存在");
        }
        return scene;
    }

    private void putMetadata(Map<String, Object> output, LlmResponseMetadata metadata) {
        if (metadata == null) {
            return;
        }
        output.put("finishReason", metadata.finishReason());
        output.put("providerRequestId", metadata.providerRequestId());
        output.put("inputTokens", metadata.inputTokens());
        output.put("outputTokens", metadata.outputTokens());
        output.put("totalTokens", metadata.totalTokens());
    }

    private Integer maxOutputTokens(Map<String, Object> input) {
        return integerValue(input.get("maxOutputTokens"));
    }

    private Double temperature(Map<String, Object> input) {
        Object value = input.get("temperature");
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private int wordCount(String content) {
        return StringUtils.hasText(content) ? content.trim().length() : 0;
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "场景规划无法序列化", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "已持久化生成数据无法读取", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SHA-256 不可用", exception);
        }
    }

    private String safeMessage(RuntimeException exception) {
        return exception instanceof BusinessException businessException ? businessException.getMessage() : "场景模型调用失败";
    }
}
