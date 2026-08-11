package com.dugnan.moqi.chapter.workflow;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy.ChapterWordRange;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy.SceneWordRange;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.context.SceneGenerationContextFocus;
import com.dugnan.moqi.context.StoryContextBuildCommand;
import com.dugnan.moqi.context.StoryContextEngine;
import com.dugnan.moqi.context.StoryContextProfile;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextSnapshotQueryPort;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.ScenePlanPromptRenderer;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;

/**
 * @author dgn
 * @date 2026-08-09
 * @description 构建或复用章节生成上下文快照并编译模型提示词。
 */
@Component
public class ChapterGenerationPromptCompiler {

    private final ScenePlanVersionMapper scenePlanMapper;
    private final StoryContextEngine contextEngine;
    private final StoryContextSnapshotQueryPort snapshotQueryPort;
    private final ChapterGenerationStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final ScenePlanPromptRenderer promptRenderer;

    public ChapterGenerationPromptCompiler(
            ScenePlanVersionMapper scenePlanMapper,
            StoryContextEngine contextEngine,
            StoryContextSnapshotQueryPort snapshotQueryPort,
            ChapterGenerationStateStore stateStore,
            ObjectMapper objectMapper,
            ScenePlanPromptRenderer promptRenderer) {
        this.scenePlanMapper = scenePlanMapper;
        this.contextEngine = contextEngine;
        this.snapshotQueryPort = snapshotQueryPort;
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
        this.promptRenderer = promptRenderer;
    }

    public StoryContextSnapshot compileSnapshot(
            ChapterGenerationEntity generation,
            ChapterGenerationSceneEntity scene,
            LlmProvider provider,
            SceneWordRange wordRange) {
        if (scene.getContextSnapshotId() != null) {
            return snapshotQueryPort.load(scene.getContextSnapshotId());
        }
        ScenePlanVersionEntity plan = requirePlan(scene.getScenePlanVersionId());
        ScenePlanContent planContent = read(plan.getContentJson(), ScenePlanContent.class);
        List<SceneGenerationContextFocus.PreviousSceneDraft> allPrevious = stateStore
                .previousCompletedScenes(generation.getId(), scene.getSequenceNo()).stream()
                .map(item -> new SceneGenerationContextFocus.PreviousSceneDraft(
                        item.getId(), item.getSceneKey(), item.getGeneratedContent()))
                .toList();
        SceneGenerationContextFocus.PreviousSceneDraft immediatePrevious = allPrevious.isEmpty()
                ? null : allPrevious.get(allPrevious.size() - 1);
        List<SceneGenerationContextFocus.PreviousSceneDraft> previous = allPrevious.isEmpty()
                ? List.of() : allPrevious.subList(0, allPrevious.size() - 1);
        ChapterGenerationSceneEntity nextScene = stateStore.nextScene(generation.getId(), scene.getSequenceNo());
        String nextSceneContent = nextScene == null ? null : scenePlanContent(nextScene.getScenePlanVersionId());
        int contextWindow = provider.capabilities().maxContextTokens() == null
                ? 16384 : provider.capabilities().maxContextTokens();
        int reserve = Math.min(
                StoryContextProfile.SCENE_GENERATION.defaultOutputReserveTokens(), contextWindow / 2);
        StoryContextSnapshot snapshot = contextEngine.build(contextBuildCommand(
                generation.getWorkId(), generation.getChapterId(), contextWindow, reserve,
                new SceneGenerationContextFocus(
                        generation.getChapterPlanVersionId(), plan.getVersion(), plan.getId(), scene.getSceneKey(),
                        chapterSceneRoute(generation.getId()), promptRenderer.render(planContent), nextSceneContent,
                        immediatePrevious, previous), wordRange));
        stateStore.markSceneRunning(scene, snapshot.id());
        return snapshot;
    }

    public StoryContextBuildCommand contextBuildCommand(
            Long workId,
            Long chapterId,
            int contextWindow,
            int outputReserve,
            SceneGenerationContextFocus focus,
            SceneWordRange wordRange) {
        return new StoryContextBuildCommand(
                StoryContextProfile.SCENE_GENERATION,
                workId,
                chapterId,
                null,
                null,
                "创作场景候选正文",
                generationInstruction(wordRange),
                null,
                contextWindow,
                outputReserve,
                null,
                focus);
    }

    public String generationInstruction(SceneWordRange wordRange) {
        return "请根据已发布的整章场景路线、当前场景、下一场目标和 Story Context 创作本场候选正文。"
                + "若提供上一场完整正文，必须从其最后一个动作和未完成目标自然续写；禁止复述已经发生的事件或台词。"
                + "持续维护时间、地点、人物位置、伤势、道具和未完成目标的连续性，不得新增或改变权威事实。正文长度必须严格控制在 "
                + wordRange.minimum() + " 至 " + wordRange.maximum()
                + " 个中文字符（含标点）之间，建议约 " + wordRange.target()
                + " 个中文字符。若尚未达到 " + wordRange.minimum()
                + " 个中文字符，不得提前收束；完成前自行核对长度，且不得超过 "
                + wordRange.maximum() + " 个中文字符。"
                + "不得改写已确认设定，不得输出分析、标题或隐藏推理。";
    }

    public String correctionInstruction(SceneWordRange wordRange, int actualWordCount) {
        String action = actualWordCount < wordRange.minimum() ? "扩写" : "压缩";
        return "上一稿共 " + actualWordCount + " 个中文字符，不符合篇幅要求。请在不改变事件、设定和结局的前提下"
                + action + "为完整正文，严格控制在 " + wordRange.minimum() + " 至 "
                + wordRange.maximum() + " 个中文字符（含标点）之间。只输出修订后的完整正文。";
    }

    public String cohesionInstruction(int targetWordCount) {
        return "你是整章小说编辑。以下是按场景生成的原始正文。请只输出一篇完整的整章正文，"
                + "仅允许补足场景间过渡、消除重复、统一节奏并修复时间、地点、人物位置、伤势、道具和未完成目标的连续性。"
                + "不得删除、改写或新增场景规划中的关键事件，也不得改变任何权威事实。"
                + "不要输出分析、标题或分场景标记；目标篇幅约 " + targetWordCount + " 字。";
    }

    public String cohesionCorrectionInstruction(ChapterWordRange wordRange, int actualWordCount) {
        String action = actualWordCount < wordRange.minimum() ? "扩写" : "压缩";
        return "上一稿共 " + actualWordCount + " 个中文字符。请在不改变场景规划、关键事件和权威事实的前提下"
                + action + "，严格控制在 " + wordRange.minimum() + " 至 " + wordRange.maximum()
                + " 个中文字符之间；只输出修订后的完整整章正文。";
    }

    private String chapterSceneRoute(Long generationId) {
        return stateStore.scenes(generationId).stream()
                .map(scene -> scenePlanContent(scene.getScenePlanVersionId()))
                .collect(Collectors.joining("\n\n"));
    }

    private String scenePlanContent(Long scenePlanVersionId) {
        ScenePlanVersionEntity plan = requirePlan(scenePlanVersionId);
        return promptRenderer.render(read(plan.getContentJson(), ScenePlanContent.class));
    }

    private ScenePlanVersionEntity requirePlan(Long scenePlanVersionId) {
        ScenePlanVersionEntity plan = scenePlanMapper.selectById(scenePlanVersionId);
        if (plan == null || Integer.valueOf(1).equals(plan.getDeleted())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_NOT_FOUND, "场景规划叶子节点不存在");
        }
        return plan;
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "已持久化生成数据无法读取", exception);
        }
    }
}
