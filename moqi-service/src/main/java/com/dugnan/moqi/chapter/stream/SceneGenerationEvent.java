package com.dugnan.moqi.chapter.stream;

import java.time.LocalDateTime;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 承载章节 SSE 所需的场景生成进度与文本增量事件。
 */
public record SceneGenerationEvent(
        String type,
        Long chapterId,
        Long generationId,
        Long generationSceneId,
        String sceneKey,
        String generationStatus,
        String sceneStatus,
        String delta,
        LocalDateTime occurredAt) {

    public static SceneGenerationEvent generation(
            String type,
            Long chapterId,
            Long generationId,
            String generationStatus) {
        return new SceneGenerationEvent(type, chapterId, generationId, null, null, generationStatus,
                null, null, LocalDateTime.now());
    }

    public static SceneGenerationEvent scene(
            String type,
            Long chapterId,
            Long generationId,
            Long generationSceneId,
            String sceneKey,
            String sceneStatus) {
        return new SceneGenerationEvent(type, chapterId, generationId, generationSceneId, sceneKey,
                null, sceneStatus, null, LocalDateTime.now());
    }

    public static SceneGenerationEvent delta(
            Long chapterId,
            Long generationId,
            Long generationSceneId,
            String sceneKey,
            String value) {
        return new SceneGenerationEvent("generation.scene.delta", chapterId, generationId,
                generationSceneId, sceneKey, null, "running", value, LocalDateTime.now());
    }

    public static SceneGenerationEvent generationDelta(
            Long chapterId,
            Long generationId,
            String value) {
        return new SceneGenerationEvent("generation.delta", chapterId, generationId,
                null, null, "running", null, value, LocalDateTime.now());
    }
}
