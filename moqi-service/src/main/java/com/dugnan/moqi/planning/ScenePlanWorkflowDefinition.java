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
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanContent;
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
        LlmResponse response = provider.generate(new LlmRequest(List.of(
                new LlmMessage(LlmRole.SYSTEM, "输出 ScenePlan JSON，对象仅含 content 与 scenes；不要输出隐藏推理。"),
                new LlmMessage(LlmRole.USER, "当前章节正式大纲：\n" + outline.getOutlineContent())),
                new LlmOptions(4096, null, List.of(), LlmResponseFormat.JSON_OBJECT)));
        if (response == null || response.structuredContent() == null) {
            throw new IllegalStateException("模型未返回结构化场景规划");
        }
        WorkflowOutput output = objectMapper.treeToValue(response.structuredContent(), WorkflowOutput.class);
        List<ScenePlanContent> scenes = codec.scenes(output.scenes());
        if (output.content() == null) {
            throw new IllegalStateException("模型未返回章节规划摘要");
        }
        String contentJson = objectMapper.writeValueAsString(output.content());
        String scenesJson = objectMapper.writeValueAsString(scenes);
        return new AgentStepResult(Map.of("contentJson", contentJson, "scenesJson", scenesJson),
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
        String contentJson = (String) result.outputSummary().get("contentJson");
        String scenesJson = (String) result.outputSummary().get("scenesJson");
        int changed = planMapper.update(null, new UpdateWrapper<ChapterPlanVersionEntity>().eq("id", candidateId)
                .eq("version", candidate.getVersion()).eq("plan_status", "queued").set("plan_status", "ready")
                .set("content_json", contentJson).setSql("version = version + 1"));
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

    private Long candidateId(AgentStepExecutionContext context) {
        Object value = context.input().get("candidateId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Agent Run 缺少 candidateId");
    }

    private record WorkflowOutput(ChapterPlanContent content, List<ScenePlanContent> scenes) {
    }
}
