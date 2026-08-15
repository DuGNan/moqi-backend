package com.dugnan.moqi.chapter.workflow;

import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
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

/**
 * @author dgn
 * @date 2026-08-09
 * @description 构建或复用章节生成上下文快照并编译模型提示词。
 */
@Component
public class ChapterGenerationPromptCompiler {

    private final StoryContextEngine contextEngine;
    private final StoryContextSnapshotQueryPort snapshotQueryPort;
    private final ChapterGenerationStateStore stateStore;
    private final ObjectMapper objectMapper;

    public ChapterGenerationPromptCompiler(
            StoryContextEngine contextEngine,
            StoryContextSnapshotQueryPort snapshotQueryPort,
            ChapterGenerationStateStore stateStore,
            ObjectMapper objectMapper) {
        this.contextEngine = contextEngine;
        this.snapshotQueryPort = snapshotQueryPort;
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
    }

    public StoryContextSnapshot compileSnapshot(
            ChapterGenerationEntity generation,
            ChapterGenerationSceneEntity scene,
            LlmProvider provider,
            SceneWordRange wordRange) {
        if (scene.getContextSnapshotId() != null) {
            return snapshotQueryPort.load(scene.getContextSnapshotId());
        }
        FrozenBrief brief = frozenBrief(generation);
        List<SceneGenerationContextFocus.PreviousSceneDraft> allPrevious = stateStore
                .previousCompletedScenes(generation.getId(), scene.getSequenceNo()).stream()
                .map(item -> new SceneGenerationContextFocus.PreviousSceneDraft(
                        item.getId(), item.getSceneKey(), item.getGeneratedContent()))
                .toList();
        SceneGenerationContextFocus.PreviousSceneDraft immediatePrevious = allPrevious.isEmpty()
                ? null : allPrevious.get(allPrevious.size() - 1);
        List<SceneGenerationContextFocus.PreviousSceneDraft> previous = allPrevious.isEmpty()
                ? List.of() : allPrevious.subList(0, allPrevious.size() - 1);
        int contextWindow = provider.capabilities().maxContextTokens() == null
                ? 16384 : provider.capabilities().maxContextTokens();
        int reserve = Math.min(
                StoryContextProfile.SCENE_GENERATION.defaultOutputReserveTokens(), contextWindow / 2);
        StoryContextSnapshot snapshot = contextEngine.build(contextBuildCommand(
                generation.getWorkId(), generation.getChapterId(), contextWindow, reserve,
                new SceneGenerationContextFocus(
                        brief.content(), brief.fingerprint(), brief.templateVersion(), scene.getSceneKey(),
                        immediatePrevious, previous), wordRange, scene.getSceneKey()));
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
        return contextBuildCommand(workId, chapterId, contextWindow, outputReserve, focus, wordRange, null);
    }

    public StoryContextBuildCommand contextBuildCommand(
            Long workId,
            Long chapterId,
            int contextWindow,
            int outputReserve,
            SceneGenerationContextFocus focus,
            SceneWordRange wordRange,
            String currentSceneKey) {
        return new StoryContextBuildCommand(
                StoryContextProfile.SCENE_GENERATION,
                workId,
                chapterId,
                null,
                null,
                "创作场景候选正文",
                generationInstruction(wordRange, currentSceneKey),
                null,
                contextWindow,
                outputReserve,
                null,
                focus);
    }

    public String generationInstruction(SceneWordRange wordRange) {
        return generationInstruction(wordRange, null);
    }

    public String generationInstruction(SceneWordRange wordRange, String currentSceneKey) {
        String currentScene = currentSceneKey == null ? "" : "当前只创作场景 " + currentSceneKey + "。";
        return "请根据冻结的 Chapter Generation Brief 和 Story Context 创作本场候选正文。"
                + currentScene
                + "若提供上一场完整正文，必须从其最后一个动作和未完成目标自然续写；禁止复述已经发生的事件或台词。"
                + "持续维护时间、地点、人物位置、伤势、道具和未完成目标的连续性，不得新增或改变权威事实。整章目标篇幅为软区间 "
                + wordRange.minimum() + " 至 " + wordRange.maximum()
                + " 个中文字符（含标点），建议约 " + wordRange.target()
                + " 字；请按当前事件的叙事权重自然分配篇幅，不得把整章目标平均摊到每个场景，"
                + "也不得为了凑字数扩写或删减不可省略的因果节点。"
                + "不得改写已确认设定，不得输出分析、标题或隐藏推理。";
    }

    public String cohesionInstruction(int targetWordCount) {
        return "你是整章小说编辑。以下是按场景生成的原始正文。请只输出一篇完整的整章正文，"
                + "仅允许补足场景间过渡、消除重复、统一节奏并修复时间、地点、人物位置、伤势、道具和未完成目标的连续性。"
                + "不得删除、改写或新增场景规划中的关键事件，也不得改变任何权威事实。"
                + "不要输出分析、标题或分场景标记；整章软目标篇幅约 " + targetWordCount
                + " 字，优先保证完整因果与自然节奏，不得为满足字数改写权威事实。";
    }

    private FrozenBrief frozenBrief(ChapterGenerationEntity generation) {
        try {
            JsonNode brief = objectMapper.readTree(generation.getBasisSnapshotJson()).path("chapterGenerationBrief");
            String content = brief.path("content").asText(null);
            String fingerprint = brief.path("fingerprint").asText(null);
            String templateVersion = brief.path("templateVersion").asText(null);
            if (content == null || fingerprint == null || templateVersion == null) {
                throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "生成批次缺少冻结的章节正文生成说明");
            }
            return new FrozenBrief(content, fingerprint, templateVersion);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "已持久化生成数据无法读取", exception);
        }
    }

    private record FrozenBrief(String content, String fingerprint, String templateVersion) {
    }
}
