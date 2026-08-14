package com.dugnan.moqi.context;

import java.util.List;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 表示经服务端校验后注入场景正文生成快照的计划与前序候选资料。
 */
public record SceneGenerationContextFocus(
        String generationBriefContent,
        String briefFingerprint,
        String briefTemplateVersion,
        String currentSceneKey,
        PreviousSceneDraft immediatePreviousScene,
        List<PreviousSceneDraft> previousScenes) {

    /**
     * 固化前序场景候选，避免调用方后续修改。
     *
     * @param generationBriefContent 冻结的人类可读章节正文生成说明
     * @param briefFingerprint 说明输入指纹
     * @param briefTemplateVersion 说明模板版本
     * @param currentSceneKey 当前生成场景键
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
