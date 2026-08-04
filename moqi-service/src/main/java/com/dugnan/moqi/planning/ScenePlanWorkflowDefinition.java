package com.dugnan.moqi.planning;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmCallContext;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderError;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseMetadata;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmRole;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 通过 Agent Runtime 生成结构化场景候选并等待人工发布。
 */
@Component
public class ScenePlanWorkflowDefinition implements AgentWorkflowDefinition {
    public static final String WORKFLOW_TYPE = "scene_plan_generation";
    private static final String GENERATE = "generate_candidate";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_READY = "ready";
    private static final String TEMPLATE_VERSION = "scene-plan-v1";
    private static final String GENERATION_PROMPT = """
            只输出 JSON 对象，顶层只能有 scenes。scenes 是 1 至 50 个对象的数组；每项必含 sceneKey、sequence、title、
            timeAnchor、goal、conflict、emotion、pacing、expectedOutcome、status。sceneKey 在本次输出内唯一，sequence
            必须从 1 开始连续递增，status 只能为 planned 或 disabled。viewpointCharacter 与 location 必须为 null；
            participants 与 requiredSettings 必须为空数组，禁止虚构设定 ID。foreshadowingActions 只能为空数组或 seed 动作，
            seed 的 foreshadowingItemId 必须为 null。不要输出 Markdown、解释或隐藏推理。
            """;

    private final ChapterPlanVersionMapper planMapper;
    private final ScenePlanVersionMapper sceneMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final LlmProviderFactory providerFactory;
    private final UserConfigService userConfigService;
    private final PlanningContentCodec codec;
    private final ObjectMapper objectMapper;

    public ScenePlanWorkflowDefinition(ChapterPlanVersionMapper planMapper, ScenePlanVersionMapper sceneMapper,
            ChapterOutlineQueryMapper outlineMapper, LlmProviderFactory providerFactory,
            UserConfigService userConfigService, PlanningContentCodec codec, ObjectMapper objectMapper) {
        this.planMapper = planMapper;
        this.sceneMapper = sceneMapper;
        this.outlineMapper = outlineMapper;
        this.providerFactory = providerFactory;
        this.userConfigService = userConfigService;
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    @Override
    public String workflowType() {
        return WORKFLOW_TYPE;
    }

    @Override
    public String startStepKey() {
        return GENERATE;
    }

    @Override
    public Duration timeout() {
        return Duration.ofDays(7);
    }

    @Override
    public int maxAttempts(String stepKey) {
        return 1;
    }

    @Override
    public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) throws Exception {
        Long candidateId = candidateId(context);
        ChapterPlanVersionEntity candidate = planMapper.selectById(candidateId);
        ChapterOutlineEntity outline = candidate == null ? null : outlineMapper.findLatest(candidate.getChapterId());
        if (candidate == null || outline == null || !candidate.getOutlineId().equals(outline.getId())
                || !candidate.getOutlineRevision().equals(outline.getRevision())) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_OUTLINE_STALE", "validation", "场景规划候选的章节大纲已过期", null);
        }
        LlmExecutionConfig executionConfig;
        LlmProvider provider;
        LlmResponse response;
        try {
            executionConfig = userConfigService.requireAvailableExecutionConfig();
            provider = providerFactory.createObserved(
                    executionConfig,
                    LlmCallContext.builder(WORKFLOW_TYPE, "generate_candidate")
                            .workId(candidate.getWorkId())
                            .chapterId(candidate.getChapterId())
                            .aiTaskId(candidate.getAiTaskId())
                            .agentRunId(context.runId())
                            .agentStepId(context.stepId())
                            .logicalCallId("agent-step:" + context.stepId() + ":scene-plan")
                            .promptTemplateVersion(TEMPLATE_VERSION)
                            .sourceFingerprint("outline:" + outline.getId() + ":" + outline.getRevision())
                            .build());
            response = provider.generate(request(outline));
        } catch (LlmProviderException exception) {
            if (LlmProviderError.INVALID_RESPONSE.equals(exception.getError())) {
                throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json", "场景规划模型返回格式无效", exception);
            }
            throw new ScenePlanWorkflowException("SCENE_PLAN_PROVIDER_FAILED", "provider", "场景规划模型调用失败", exception);
        } catch (Exception exception) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_PROVIDER_FAILED", "provider", "场景规划模型调用失败", exception);
        }
        if (response == null || response.structuredContent() == null) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json", "场景规划模型返回格式无效", null);
        }
        WorkflowOutput output;
        try {
            validateOutputShape(response.structuredContent());
            output = objectMapper.treeToValue(response.structuredContent(), WorkflowOutput.class);
        } catch (Exception exception) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json", "场景规划模型返回格式无效", exception);
        }
        List<ScenePlanContent> scenes;
        try {
            scenes = codec.scenes(output.scenes());
            validateGeneratedReferences(scenes);
        } catch (Exception exception) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_VALIDATION_FAILED", "validation", "场景规划结构校验失败", exception);
        }
        String scenesJson = objectMapper.writeValueAsString(scenes);
        LlmResponseMetadata metadata = response.metadata();
        Long modelCallId = metadata == null ? null : metadata.modelCallId();
        return new AgentStepResult(Map.of("scenesJson", scenesJson),
                Map.of("candidateId", candidateId), null,
                modelCallId == null ? null : String.valueOf(modelCallId), null);
    }

    @Override
    public void applyResult(String stepKey, AgentStepExecutionContext context, AgentStepResult result) {
        if (!GENERATE.equals(stepKey)) {
            return;
        }
        Long candidateId = candidateId(context);
        ChapterPlanVersionEntity candidate = planMapper.selectById(candidateId);
        if (candidate == null || !STATUS_QUEUED.equals(candidate.getPlanStatus())) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_PERSISTENCE_FAILED", "persistence", "场景候选状态已变化", null);
        }
        List<ScenePlanContent> scenes = persistedScenes(result);
        try {
            int changed = planMapper.update(null, new UpdateWrapper<ChapterPlanVersionEntity>().eq("id", candidateId)
                    .eq("version", candidate.getVersion()).eq("plan_status", STATUS_QUEUED)
                    .set("plan_status", STATUS_READY)
                    .setSql("version = version + 1"));
            if (changed != 1) {
                throw new ScenePlanWorkflowException("SCENE_PLAN_PERSISTENCE_FAILED", "persistence", "场景候选状态已变化", null);
            }
            for (ScenePlanContent scene : scenes) {
                ScenePlanVersionEntity entity = new ScenePlanVersionEntity();
                entity.setChapterPlanVersionId(candidateId);
                entity.setSceneKey(scene.sceneKey());
                entity.setSequenceNo(scene.sequence());
                entity.setContentJson(objectMapper.writeValueAsString(scene));
                entity.setDeleted(0);
                entity.setVersion(0);
                sceneMapper.insert(entity);
            }
        } catch (Exception exception) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_PERSISTENCE_FAILED", "persistence", "场景候选持久化失败", exception);
        }
    }

    @Override
    public String errorCategory(Exception exception) {
        return exception instanceof ScenePlanWorkflowException workflowException
                ? workflowException.category() : "persistence";
    }

    @Override
    public String errorCode(Exception exception) {
        return exception instanceof ScenePlanWorkflowException workflowException
                ? workflowException.code() : "SCENE_PLAN_PERSISTENCE_FAILED";
    }

    @Override
    public void applyFailure(String stepKey, AgentStepExecutionContext context, Exception exception) {
        if (!GENERATE.equals(stepKey)) {
            return;
        }
        Long candidateId = candidateId(context);
        planMapper.update(null, new UpdateWrapper<ChapterPlanVersionEntity>().eq("id", candidateId)
                .eq("deleted", 0).eq("plan_status", STATUS_QUEUED).set("plan_status", STATUS_FAILED)
                .setSql("version = version + 1"));
    }

    private Long candidateId(AgentStepExecutionContext context) {
        Object value = context.input().get("candidateId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Agent Run 缺少 candidateId");
    }

    private List<ScenePlanContent> persistedScenes(AgentStepResult result) {
        Object value = result.outputSummary().get("scenesJson");
        if (!(value instanceof String scenesJson)) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json", "场景规划结果缺少 scenesJson", null);
        }
        try {
            List<ScenePlanContent> scenes = objectMapper.readValue(scenesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ScenePlanContent.class));
            return codec.scenes(scenes);
        } catch (Exception exception) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_VALIDATION_FAILED", "validation", "场景规划结果无法持久化", exception);
        }
    }

    private LlmRequest request(ChapterOutlineEntity outline) {
        return new LlmRequest(List.of(
                new LlmMessage(LlmRole.SYSTEM, GENERATION_PROMPT),
                new LlmMessage(LlmRole.USER, "当前章节正式大纲：\n" + outline.getOutlineContent())),
                new LlmOptions(4096, null, List.of(), LlmResponseFormat.JSON_OBJECT));
    }

    private void validateGeneratedReferences(List<ScenePlanContent> scenes) {
        for (ScenePlanContent scene : scenes) {
            boolean hasSettingReference = scene.viewpointCharacter() != null || scene.location() != null
                    || !scene.participants().isEmpty() || !scene.requiredSettings().isEmpty();
            boolean hasExistingForeshadowing = scene.foreshadowingActions().stream()
                    .anyMatch(action -> action.foreshadowingItemId() != null);
            if (hasSettingReference || hasExistingForeshadowing) {
                throw new IllegalArgumentException("场景规划不能引用未提供的设定或既有伏笔");
            }
        }
    }

    private void validateOutputShape(JsonNode structuredContent) {
        if (!structuredContent.isObject()
                || structuredContent.size() != 1
                || !structuredContent.has("scenes")) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json",
                    "场景规划模型响应必须是仅包含 scenes 的 JSON 对象", null);
        }
    }

    private record WorkflowOutput(List<ScenePlanContent> scenes) {
    }

    private static final class ScenePlanWorkflowException extends RuntimeException {
        private final String code;
        private final String category;

        private ScenePlanWorkflowException(String code, String category, String message, Exception cause) {
            super(message, cause);
            this.code = code;
            this.category = category;
        }

        private String code() {
            return code;
        }

        private String category() {
            return category;
        }
    }
}
