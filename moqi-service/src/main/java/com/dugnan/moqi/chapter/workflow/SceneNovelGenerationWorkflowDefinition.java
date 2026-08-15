package com.dugnan.moqi.chapter.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy.SceneWordRange;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationModelInvoker.SceneInvocationContext;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.context.StoryContextSnapshot;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 通过 Agent Runtime 分派章节正文生成步骤并编排状态迁移。
 */
@Component
public class SceneNovelGenerationWorkflowDefinition implements AgentWorkflowDefinition {

    public static final String WORKFLOW_TYPE = "scene_novel_generation";
    static final String LOAD = "load_generation";
    private static final int MAX_ATTEMPTS = 3;

    private final ChapterGenerationStateStore stateStore;
    private final ChapterGenerationStepPlanner stepPlanner;
    private final ChapterGenerationLengthPolicy lengthPolicy;
    private final ChapterGenerationPromptCompiler promptCompiler;
    private final ChapterGenerationModelInvoker modelInvoker;
    private final ChapterGenerationCompletionHandler completionHandler;

    public SceneNovelGenerationWorkflowDefinition(
            ChapterGenerationStateStore stateStore,
            ChapterGenerationStepPlanner stepPlanner,
            ChapterGenerationLengthPolicy lengthPolicy,
            ChapterGenerationPromptCompiler promptCompiler,
            ChapterGenerationModelInvoker modelInvoker,
            ChapterGenerationCompletionHandler completionHandler) {
        this.stateStore = stateStore;
        this.stepPlanner = stepPlanner;
        this.lengthPolicy = lengthPolicy;
        this.promptCompiler = promptCompiler;
        this.modelInvoker = modelInvoker;
        this.completionHandler = completionHandler;
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
            ChapterGenerationEntity generation = stateStore.requireGeneration(generationId);
            return AgentStepResult.completed(Map.of("generationId", generation.getId()), Map.of(),
                    stepPlanner.nextStep(generationId, 0));
        }
        if (ChapterGenerationStepPlanner.FINALIZE.equals(stepKey)) {
            return AgentStepResult.completed(Map.of("generationId", generationId), Map.of(), null);
        }
        if (ChapterGenerationStepPlanner.COHERE.equals(stepKey)) {
            return cohereChapter(context, generationId);
        }
        if (ChapterGenerationStepPlanner.GENERATE_CHAPTER.equals(stepKey)) {
            return generateWholeChapter(context, generationId);
        }
        if (!stepKey.startsWith(ChapterGenerationStepPlanner.GENERATE_PREFIX)) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "场景生成步骤键不合法");
        }
        return generateScene(stepKey.substring(ChapterGenerationStepPlanner.GENERATE_PREFIX.length()),
                context, generationId);
    }

    @Override
    public void applyResult(String stepKey, AgentStepExecutionContext context, AgentStepResult result) {
        Long generationId = generationId(context);
        if (LOAD.equals(stepKey)) {
            completionHandler.generationStarted(stateStore.markStarted(generationId));
            return;
        }
        if (ChapterGenerationStepPlanner.FINALIZE.equals(stepKey)) {
            completionHandler.generationCompleted(stateStore.finalizeGeneration(generationId));
            return;
        }
        if (ChapterGenerationStepPlanner.COHERE.equals(stepKey)) {
            stateStore.applyCohesionResult(generationId, result);
            return;
        }
        if (ChapterGenerationStepPlanner.GENERATE_CHAPTER.equals(stepKey)) {
            stateStore.applyWholeChapterResult(generationId, result);
            return;
        }
        ChapterGenerationSceneEntity scene = stateStore.applySceneResult(generationId, result);
        if (scene != null) {
            completionHandler.sceneCompleted(stateStore.requireGeneration(generationId), scene);
        }
    }

    @Override
    public void applyFailure(String stepKey, AgentStepExecutionContext context, Exception exception) {
        ChapterGenerationEntity generation = stateStore.markFailed(
                generationId(context), stepKey,
                ChapterGenerationStepPlanner.GENERATE_PREFIX,
                ChapterGenerationStepPlanner.COHERE);
        completionHandler.generationFailed(generation);
    }

    private AgentStepResult generateScene(
            String sceneKey,
            AgentStepExecutionContext context,
            Long generationId) {
        ChapterGenerationEntity generation = stateStore.requireGeneration(generationId);
        ChapterGenerationSceneEntity scene = stateStore.requireScene(generationId, sceneKey);
        String nextStep = stepPlanner.nextStep(generationId, scene.getSequenceNo());
        if (ChapterGenerationStateStore.SCENE_COMPLETED.equals(scene.getSceneStatus())
                || ChapterGenerationStateStore.SCENE_COPIED.equals(scene.getSceneStatus())) {
            return AgentStepResult.completed(Map.of("sceneId", scene.getId(), "skipped", true), Map.of(), nextStep);
        }
        SceneWordRange wordRange = wordRange(context.input());
        SceneInvocationContext invocationContext = modelInvoker.prepareScene(generation);
        StoryContextSnapshot snapshot = promptCompiler.compileSnapshot(
                generation, scene, invocationContext.contextProvider(), wordRange);
        return modelInvoker.generateScene(
                generation, scene, snapshot, wordRange, invocationContext, context, nextStep);
    }

    private AgentStepResult cohereChapter(AgentStepExecutionContext context, Long generationId) {
        ChapterGenerationEntity generation = stateStore.requireGeneration(generationId);
        List<ChapterGenerationSceneEntity> scenes = stateStore.completedScenes(generationId);
        if (scenes.isEmpty()) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, "没有可供整章收束的场景正文");
        }
        stateStore.markCohesionRunning(generationId);
        return modelInvoker.cohereChapter(generation, scenes,
                targetChapterWordCount(context.input()), context);
    }

    private AgentStepResult generateWholeChapter(AgentStepExecutionContext context, Long generationId) {
        ChapterGenerationEntity generation = stateStore.requireGeneration(generationId);
        if (org.springframework.util.StringUtils.hasText(generation.getGeneratedContent())
                && org.springframework.util.StringUtils.hasText(generation.getGenerationTemplateVersion())) {
            return AgentStepResult.completed(Map.of("skipped", true), Map.of(),
                    ChapterGenerationStepPlanner.FINALIZE);
        }
        int targetWordCount = targetChapterWordCount(context.input());
        return modelInvoker.generateWholeChapter(
                generation,
                promptCompiler.compileWholeChapter(generation, targetWordCount),
                targetWordCount,
                context);
    }

    private SceneWordRange wordRange(Map<String, Object> input) {
        Integer targetWordCount = integerValue(input.get("targetChapterWordCount"));
        if (targetWordCount == null || targetWordCount <= 0) {
            targetWordCount = lengthPolicy.resolveTargetWordCount(ChapterGenerationLengthPolicy.DEFAULT_PRESET, null);
        }
        return lengthPolicy.sceneSoftRange(targetWordCount);
    }

    private int targetChapterWordCount(Map<String, Object> input) {
        Integer target = integerValue(input.get("targetChapterWordCount"));
        return target != null && target > 0 ? target
                : lengthPolicy.resolveTargetWordCount(ChapterGenerationLengthPolicy.DEFAULT_PRESET, null);
    }

    private Long generationId(AgentStepExecutionContext context) {
        Object value = context.input().get("generationId");
        Long generationId = value instanceof Number number ? number.longValue() : null;
        if (generationId == null) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "场景生成运行缺少 generationId");
        }
        return generationId;
    }

    private Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
