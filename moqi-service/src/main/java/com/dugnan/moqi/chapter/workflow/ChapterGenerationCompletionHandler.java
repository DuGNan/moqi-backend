package com.dugnan.moqi.chapter.workflow;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.stream.SceneGenerationEvent;

/**
 * @author dgn
 * @date 2026-08-09
 * @description 发布章节生成生命周期事件并隔离后续评价与修订接入点。
 */
@Component
public class ChapterGenerationCompletionHandler {

    private final ApplicationEventPublisher eventPublisher;

    public ChapterGenerationCompletionHandler(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void generationStarted(ChapterGenerationEntity generation) {
        eventPublisher.publishEvent(SceneGenerationEvent.generation(
                "generation.started", generation.getChapterId(), generation.getId(), "running"));
    }

    public void sceneStarted(ChapterGenerationEntity generation, ChapterGenerationSceneEntity scene) {
        eventPublisher.publishEvent(SceneGenerationEvent.scene(
                "generation.scene.started", generation.getChapterId(), generation.getId(),
                scene.getId(), scene.getSceneKey(), "running"));
    }

    public void sceneDelta(
            ChapterGenerationEntity generation,
            ChapterGenerationSceneEntity scene,
            String delta) {
        eventPublisher.publishEvent(SceneGenerationEvent.delta(
                generation.getChapterId(), generation.getId(), scene.getId(), scene.getSceneKey(), delta));
    }

    public void sceneCompleted(ChapterGenerationEntity generation, ChapterGenerationSceneEntity scene) {
        eventPublisher.publishEvent(SceneGenerationEvent.scene(
                "generation.scene.completed", generation.getChapterId(), generation.getId(),
                scene.getId(), scene.getSceneKey(), "completed"));
    }

    public void generationDelta(ChapterGenerationEntity generation, String delta) {
        eventPublisher.publishEvent(SceneGenerationEvent.generationDelta(
                generation.getChapterId(), generation.getId(), delta));
    }

    public void generationFailed(ChapterGenerationEntity generation) {
        eventPublisher.publishEvent(SceneGenerationEvent.generation(
                "generation.failed", generation.getChapterId(), generation.getId(), "failed"));
    }

    public void generationCompleted(ChapterGenerationEntity generation) {
        eventPublisher.publishEvent(SceneGenerationEvent.generation(
                "generation.completed", generation.getChapterId(), generation.getId(), "preview"));
    }
}
