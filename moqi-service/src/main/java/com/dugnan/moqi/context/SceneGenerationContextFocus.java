package com.dugnan.moqi.context;

import java.util.List;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 表示经服务端校验后注入场景正文生成快照的计划与前序候选资料。
 */
public record SceneGenerationContextFocus(
        Long chapterPlanVersionId,
        Integer chapterPlanNo,
        Long scenePlanVersionId,
        String sceneKey,
        String sceneContent,
        List<PreviousSceneDraft> previousScenes) {

    /**
     * 固化前序场景候选，避免调用方后续修改。
     *
     * @param chapterPlanVersionId 章节规划版本 ID
     * @param chapterPlanNo 章节规划版本号
     * @param scenePlanVersionId 场景规划叶子 ID
     * @param sceneKey 场景键
     * @param sceneContent 场景规划内容
     * @param previousScenes 已完成前序场景候选
     */
    public SceneGenerationContextFocus {
        previousScenes = previousScenes == null ? List.of() : List.copyOf(previousScenes);
    }

    /**
     * @param generationSceneId 场景候选 ID
     * @param sceneKey 场景键
     * @param content 候选正文
     */
    public record PreviousSceneDraft(Long generationSceneId, String sceneKey, String content) {
    }
}
