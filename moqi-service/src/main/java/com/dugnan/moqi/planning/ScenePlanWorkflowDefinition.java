package com.dugnan.moqi.planning;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContentCodec;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextSnapshotQueryPort;
import com.dugnan.moqi.context.StoryContextSourceType;
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
    private static final String TEMPLATE_VERSION = "scene-plan-v2";
    private static final String SCENES_FIELD = "scenes";
    private static final String ACTION_FIELD = "action";
    private static final String SEED_ACTION = "seed";
    private static final Set<String> REQUIRED_SCENE_FIELDS = Set.of(
            "sceneKey", "sequence", "title", "viewpointCharacter", "timeAnchor", "location", "goal", "conflict",
            "emotion", "pacing", "participants", "requiredSettings", "foreshadowingActions", "expectedOutcome",
            "status", "outlineBeatKeys", "readerMustKnow", "causalPreconditions", "locationTransition",
            "stateChanges", "continuityConstraints", "narrativeWeight", "optionalExpression", "doNotInvent");
    private static final String GENERATION_PROMPT = """
            仅输出 JSON 对象，顶层只能包含 scenes。每个场景必须包含 sceneKey、sequence、title、
            viewpointCharacter、timeAnchor、location、goal、conflict、emotion、pacing、participants、
            requiredSettings、foreshadowingActions、expectedOutcome、status、outlineBeatKeys、readerMustKnow、
            causalPreconditions、locationTransition、stateChanges、continuityConstraints、narrativeWeight、
            optionalExpression 和 doNotInvent；sequence 从 1 连续递增。
            readerMustKnow、causalPreconditions、stateChanges、continuityConstraints、optionalExpression、
            doNotInvent 必须是数组，可以为空；locationTransition 必须是字符串，可以为空。
            viewpointCharacter 和 location 只能为 null 或 PlanReference 对象；PlanReference 只能包含
            settingEntryId（JSON 整数）和 name（字符串）。participants 和 requiredSettings 必须是
            PlanReference 对象数组。foreshadowingActions 必须是对象数组，每项只能包含 action
            （seed、advance 或 payoff）、foreshadowingItemId（JSON 整数或 null）和 description（字符串）。
            上下文没有列出可用 ID 时，引用字段必须使用 null 或空数组，不得把格式说明中的文字或数字当成 ID。
            advance 和 payoff 必须引用上下文中的既有伏笔 ID；没有既有伏笔时，foreshadowingActions 只能为空数组，
            或使用 seed 且 foreshadowingItemId 为 null，表示仅生成待人工确认的新伏笔候选。
            narrativeWeight 只能是 core、supporting 或 transition，不得输出 unspecified。
            status 必须为 planned。仅能引用上下文中已列出的设定与伏笔 ID，禁止编造 ID。
            不要输出 Markdown、解释或隐藏推理。
            """;

    private final ChapterPlanVersionMapper planMapper;
    private final ScenePlanVersionMapper sceneMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final LlmProviderFactory providerFactory;
    private final UserConfigService userConfigService;
    private final StoryContextSnapshotQueryPort snapshotQueryPort;
    private final PlanningContentCodec codec;
    private final OutlineCandidateContentCodec outlineCodec;
    private final ObjectMapper objectMapper;

    public ScenePlanWorkflowDefinition(ChapterPlanVersionMapper planMapper, ScenePlanVersionMapper sceneMapper,
            ChapterOutlineQueryMapper outlineMapper, LlmProviderFactory providerFactory,
            UserConfigService userConfigService, StoryContextSnapshotQueryPort snapshotQueryPort,
            PlanningContentCodec codec, OutlineCandidateContentCodec outlineCodec, ObjectMapper objectMapper) {
        this.planMapper = planMapper;
        this.sceneMapper = sceneMapper;
        this.outlineMapper = outlineMapper;
        this.providerFactory = providerFactory;
        this.userConfigService = userConfigService;
        this.snapshotQueryPort = snapshotQueryPort;
        this.codec = codec;
        this.outlineCodec = outlineCodec;
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
        StoryContextSnapshot snapshot = contextSnapshot(context);
        List<String> outlineBeatKeys = outlineBeatKeys(outline);
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
                            .sourceFingerprint(snapshot.contentHash())
                            .build());
            response = provider.generate(request(snapshot, outlineBeatKeys));
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
            JsonNode normalizedContent = normalizeGeneratedReferences(response.structuredContent(), snapshot);
            output = objectMapper.treeToValue(normalizedContent, WorkflowOutput.class);
        } catch (Exception exception) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json", "场景规划模型返回格式无效", exception);
        }
        List<ScenePlanContent> scenes;
        try {
            scenes = codec.scenes(output.scenes());
            validateGeneratedReferences(scenes, snapshot, outlineBeatKeys);
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
        ChapterOutlineEntity outline = outlineMapper.findLatest(candidate.getChapterId());
        if (outline == null || !candidate.getOutlineId().equals(outline.getId())
                || !candidate.getOutlineRevision().equals(outline.getRevision())) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_SOURCE_STALE", "validation", "场景规划来源已更新", null);
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
                entity.setContentSchemaVersion(PlanningContentCodec.CURRENT_SCENE_CONTENT_SCHEMA_VERSION);
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
        String status = exception instanceof ScenePlanWorkflowException workflowException
                && "SCENE_PLAN_SOURCE_STALE".equals(workflowException.code()) ? "stale" : STATUS_FAILED;
        planMapper.update(null, new UpdateWrapper<ChapterPlanVersionEntity>().eq("id", candidateId)
                .eq("deleted", 0).eq("plan_status", STATUS_QUEUED).set("plan_status", status)
                .set("validity_status", "stale".equals(status) ? "stale" : "current")
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

    private StoryContextSnapshot contextSnapshot(AgentStepExecutionContext context) {
        Object value = context.input().get("contextSnapshotId");
        if (!(value instanceof Number number)) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_SOURCE_STALE", "validation", "场景规划缺少已固化上下文", null);
        }
        return snapshotQueryPort.load(number.longValue());
    }

    private LlmRequest request(StoryContextSnapshot snapshot, List<String> outlineBeatKeys) throws Exception {
        List<LlmMessage> messages = new ArrayList<>(snapshot.toMessages());
        messages.add(new LlmMessage(com.dugnan.moqi.llm.LlmRole.SYSTEM, GENERATION_PROMPT));
        messages.add(new LlmMessage(com.dugnan.moqi.llm.LlmRole.SYSTEM,
                "每个场景必须包含非空 outlineBeatKeys 数组；只能使用以下正式章纲节拍键，必须全部覆盖且首次出现顺序不得倒置："
                        + objectMapper.writeValueAsString(outlineBeatKeys)));
        return new LlmRequest(messages, new LlmOptions(4096, null, List.of(), LlmResponseFormat.JSON_OBJECT));
    }

    private void validateGeneratedReferences(List<ScenePlanContent> scenes, StoryContextSnapshot snapshot,
            List<String> outlineBeatKeys) {
        Set<Long> settingIds = selectedIds(snapshot, StoryContextSourceType.SETTING_ENTRY);
        Set<Long> foreshadowingIds = selectedIds(snapshot, StoryContextSourceType.FORESHADOWING);
        Set<String> allowedBeatKeys = Set.copyOf(outlineBeatKeys);
        Map<String, Integer> firstSequence = new LinkedHashMap<>();
        for (ScenePlanContent scene : scenes) {
            if (!"planned".equals(scene.status())) {
                throw new IllegalArgumentException("模型生成的场景必须为 planned");
            }
            if (!hasAllowedReferences(scene, settingIds, foreshadowingIds)) {
                throw new IllegalArgumentException("场景规划引用了未选入上下文的设定或伏笔");
            }
            if (scene.outlineBeatKeys().isEmpty()) {
                throw new IllegalArgumentException("每个场景必须关联至少一个正式章纲节拍");
            }
            for (String beatKey : scene.outlineBeatKeys()) {
                if (!allowedBeatKeys.contains(beatKey)) {
                    throw new IllegalArgumentException("场景引用了不存在的正式章纲节拍");
                }
                firstSequence.merge(beatKey, scene.sequence(), Math::min);
            }
        }
        int previousSequence = -1;
        for (String beatKey : outlineBeatKeys) {
            Integer sequence = firstSequence.get(beatKey);
            if (sequence == null) {
                throw new IllegalArgumentException("场景规划未覆盖全部正式章纲节拍");
            }
            if (sequence < previousSequence) {
                throw new IllegalArgumentException("场景规划的章纲节拍顺序发生倒置");
            }
            previousSequence = sequence;
        }
    }

    private List<String> outlineBeatKeys(ChapterOutlineEntity outline) {
        OutlineCandidateContent content = outlineCodec.read(outline.getOutlineContent());
        return content.beats().stream().map(OutlineCandidateContent.Beat::beatKey).toList();
    }

    private boolean hasAllowedReferences(ScenePlanContent scene, Set<Long> settingIds, Set<Long> foreshadowingIds) {
        return referencesWithin(scene.viewpointCharacter(), settingIds)
                && referencesWithin(scene.location(), settingIds)
                && scene.participants().stream().allMatch(reference -> referencesWithin(reference, settingIds))
                && scene.requiredSettings().stream().allMatch(reference -> referencesWithin(reference, settingIds))
                && scene.foreshadowingActions().stream().allMatch(action -> action.foreshadowingItemId() == null
                        || foreshadowingIds.contains(action.foreshadowingItemId()));
    }

    private Set<Long> selectedIds(StoryContextSnapshot snapshot, StoryContextSourceType type) {
        Set<Long> result = new HashSet<>();
        snapshot.items().stream().filter(item -> item.sourceType() == type).forEach(item -> {
            try {
                result.add(Long.valueOf(item.sourceId()));
            } catch (NumberFormatException exception) {
                // 仅忽略非实体来源；此处不会放宽任何模型引用校验。
            }
        });
        return result;
    }

    private boolean referencesWithin(PlanningModels.PlanReference reference, Set<Long> allowedIds) {
        return reference == null || reference.settingEntryId() != null && allowedIds.contains(reference.settingEntryId());
    }

    /**
     * 丢弃模型输出中没有上下文来源的实体引用，不补写或替换任何事实。
     *
     * @param structuredContent 模型结构化响应
     * @param snapshot 本次生成使用的固化上下文
     * @return 仅移除无来源引用后的响应副本
     */
    private JsonNode normalizeGeneratedReferences(JsonNode structuredContent, StoryContextSnapshot snapshot) {
        ObjectNode normalized = structuredContent.deepCopy();
        Set<Long> settingIds = selectedIds(snapshot, StoryContextSourceType.SETTING_ENTRY);
        Set<Long> foreshadowingIds = selectedIds(snapshot, StoryContextSourceType.FORESHADOWING);
        for (JsonNode value : normalized.withArray(SCENES_FIELD)) {
            ObjectNode scene = (ObjectNode) value;
            normalizeReference(scene, "viewpointCharacter", settingIds);
            normalizeReference(scene, "location", settingIds);
            normalizeReferenceArray(scene, "participants", settingIds);
            normalizeReferenceArray(scene, "requiredSettings", settingIds);
            normalizeForeshadowingActions(scene, foreshadowingIds);
        }
        return normalized;
    }

    private void normalizeReference(ObjectNode scene, String field, Set<Long> allowedIds) {
        if (!allowedReference(scene.get(field), allowedIds)) {
            scene.putNull(field);
        }
    }

    private void normalizeReferenceArray(ObjectNode scene, String field, Set<Long> allowedIds) {
        ArrayNode normalized = objectMapper.createArrayNode();
        JsonNode values = scene.get(field);
        if (values != null && values.isArray()) {
            values.forEach(value -> {
                if (allowedReference(value, allowedIds)) {
                    normalized.add(value.deepCopy());
                }
            });
        }
        scene.set(field, normalized);
    }

    private void normalizeForeshadowingActions(ObjectNode scene, Set<Long> allowedIds) {
        ArrayNode normalized = objectMapper.createArrayNode();
        JsonNode values = scene.get("foreshadowingActions");
        if (values != null && values.isArray()) {
            values.forEach(value -> {
                if (allowedForeshadowingAction(value, allowedIds)) {
                    normalized.add(value.deepCopy());
                }
            });
        }
        scene.set("foreshadowingActions", normalized);
    }

    private boolean allowedReference(JsonNode reference, Set<Long> allowedIds) {
        JsonNode id = reference == null || !reference.isObject() ? null : reference.get("settingEntryId");
        return id != null && id.canConvertToLong() && allowedIds.contains(id.longValue());
    }

    private boolean allowedForeshadowingAction(JsonNode value, Set<Long> allowedIds) {
        if (value == null || !value.isObject() || !value.path(ACTION_FIELD).isTextual()) {
            return false;
        }
        String action = value.path(ACTION_FIELD).asText();
        JsonNode id = value.get("foreshadowingItemId");
        if (SEED_ACTION.equals(action) && missingId(id)) {
            return true;
        }
        return id != null && id.canConvertToLong() && allowedIds.contains(id.longValue());
    }

    private boolean missingId(JsonNode id) {
        return id == null || id.isNull();
    }

    private void validateOutputShape(JsonNode structuredContent) {
        if (!structuredContent.isObject()
                || structuredContent.size() != 1
                || !structuredContent.has("scenes")) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json",
                    "场景规划模型响应必须是仅包含 scenes 的 JSON 对象", null);
        }
        JsonNode scenes = structuredContent.get("scenes");
        if (!scenes.isArray() || scenes.isEmpty()) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json",
                    "场景规划模型响应的 scenes 必须是非空数组", null);
        }
        for (JsonNode scene : scenes) {
            if (!scene.isObject() || !REQUIRED_SCENE_FIELDS.stream().allMatch(scene::has)) {
                throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json",
                        "场景规划模型响应缺少 v2 必填字段", null);
            }
            if (!scene.get("readerMustKnow").isArray()
                    || !scene.get("causalPreconditions").isArray()
                    || !scene.get("stateChanges").isArray()
                    || !scene.get("continuityConstraints").isArray()
                    || !scene.get("optionalExpression").isArray()
                    || !scene.get("doNotInvent").isArray()
                    || !scene.get("locationTransition").isTextual()
                    || !scene.get("narrativeWeight").isTextual()) {
                throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json",
                        "场景规划模型响应的 v2 字段类型无效", null);
            }
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
