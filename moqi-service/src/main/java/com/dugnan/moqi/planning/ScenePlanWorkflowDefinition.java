package com.dugnan.moqi.planning;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentInterruptionRequest;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
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
    private static final String PUBLISH = "publish_candidate";

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
        return GENERATE.equals(stepKey) ? 2 : 1;
    }

    @Override
    public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) throws Exception {
        if (PUBLISH.equals(stepKey)) {
            return AgentStepResult.completed(Map.of("published", true), Map.of("candidateId", candidateId(context)), null);
        }
        Long candidateId = candidateId(context);
        ChapterPlanVersionEntity candidate = planMapper.selectById(candidateId);
        ChapterOutlineEntity outline = candidate == null ? null : outlineMapper.findLatest(candidate.getChapterId());
        if (candidate == null || outline == null || !candidate.getOutlineId().equals(outline.getId())
                || !candidate.getOutlineRevision().equals(outline.getRevision())) {
            throw new IllegalStateException("场景规划候选的章节大纲已过期");
        }
        LlmProvider provider = providerFactory.create(userConfigService.requireAvailableModelConfig());
        LlmResponse response;
        try {
            response = provider.generate(new LlmRequest(List.of(
                new LlmMessage(LlmRole.SYSTEM, "只输出 JSON 对象：scenes 是 1 至 50 个对象的数组，每项必含 sceneKey、sequence、title、timeAnchor、goal、"
                        + "conflict、emotion、pacing、expectedOutcome、status。status 只能为 planned 或 disabled，"
                        + "sequence 从 1 开始连续递增；participants、requiredSettings、foreshadowingActions 可为空数组。"
                        + "不要输出 Markdown、解释或隐藏推理。"),
                new LlmMessage(LlmRole.USER, "当前章节正式大纲：\n" + outline.getOutlineContent())),
                new LlmOptions(4096, null, List.of(), LlmResponseFormat.JSON_OBJECT)));
        } catch (Exception exception) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_PROVIDER_FAILED", "provider", "场景规划模型调用失败", exception);
        }
        if (response == null || response.structuredContent() == null) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json", "场景规划模型返回格式无效", null);
        }
        WorkflowOutput output;
        try {
            output = objectMapper.treeToValue(response.structuredContent(), WorkflowOutput.class);
        } catch (Exception exception) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_JSON_INVALID", "json", "场景规划模型返回格式无效", exception);
        }
        List<ScenePlanContent> scenes;
        try {
            scenes = codec.scenes(output.scenes());
        } catch (Exception exception) {
            throw new ScenePlanWorkflowException("SCENE_PLAN_VALIDATION_FAILED", "validation", "场景规划结构校验失败", exception);
        }
        String scenesJson = objectMapper.writeValueAsString(scenes);
        return new AgentStepResult(Map.of("scenesJson", scenesJson),
                Map.of("candidateId", candidateId), PUBLISH,
                response.metadata() == null ? null : response.metadata().providerRequestId(),
                new AgentInterruptionRequest("scene_plan_publish", Map.of("candidateId", candidateId), LocalDateTime.now().plusDays(7)));
    }

    @Override
    public void applyResult(String stepKey, AgentStepExecutionContext context, AgentStepResult result) {
        if (!GENERATE.equals(stepKey)) {
            return;
        }
        Long candidateId = candidateId(context);
        ChapterPlanVersionEntity candidate = planMapper.selectById(candidateId);
        if (candidate == null || !"queued".equals(candidate.getPlanStatus())) {
            return;
        }
        String scenesJson = (String) result.outputSummary().get("scenesJson");
        int changed = planMapper.update(null, new UpdateWrapper<ChapterPlanVersionEntity>().eq("id", candidateId)
                .eq("version", candidate.getVersion()).eq("plan_status", "queued").set("plan_status", "ready")
                .setSql("version = version + 1"));
        if (changed != 1) {
            return;
        }
        try {
            List<ScenePlanContent> scenes = objectMapper.readValue(scenesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ScenePlanContent.class));
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
            throw new IllegalStateException("场景候选持久化失败", exception);
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
                .eq("deleted", 0).eq("plan_status", "queued").set("plan_status", "failed")
                .setSql("version = version + 1"));
    }

    private Long candidateId(AgentStepExecutionContext context) {
        Object value = context.input().get("candidateId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Agent Run 缺少 candidateId");
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
