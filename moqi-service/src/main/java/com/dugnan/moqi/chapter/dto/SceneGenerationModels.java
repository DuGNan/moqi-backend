package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 集中定义逐场景小说生成工作流的接口数据模型。
 */
public final class SceneGenerationModels {

    private SceneGenerationModels() {
    }

    public record CreateSceneGenerationRequest(
            Integer scenePlanNo,
            String selectionMode,
            String fromSceneKey,
            List<String> sceneKeys,
            Long baseGenerationId,
            String idempotencyKey,
            String lengthPreset,
            Integer customWordCount,
            Double temperature,
            Long capacityAssessmentId,
            String capacityDecision) {

        public CreateSceneGenerationRequest {
            sceneKeys = sceneKeys == null ? List.of() : List.copyOf(sceneKeys);
        }

        /** 兼容容量评估契约发布前的服务端调用。 */
        public CreateSceneGenerationRequest(
                Integer scenePlanNo,
                String selectionMode,
                String fromSceneKey,
                List<String> sceneKeys,
                Long baseGenerationId,
                String idempotencyKey,
                String lengthPreset,
                Integer customWordCount,
                Double temperature) {
            this(scenePlanNo, selectionMode, fromSceneKey, sceneKeys, baseGenerationId, idempotencyKey,
                    lengthPreset, customWordCount, temperature, null, null);
        }
    }

    public record SceneGenerationCreated(
            Long generationId,
            Long aiTaskId,
            Long agentRunId,
            Long chapterPlanVersionId,
            String generationStatus,
            LocalDateTime gmtCreate) {
    }

    public record GenerationSceneView(
            Long id,
            Long generationId,
            Long scenePlanVersionId,
            String sceneKey,
            Integer sequenceNo,
            String sceneStatus,
            String generatedContent,
            Long contextSnapshotId,
            String promptTemplateVersion,
            Integer wordCount,
            Long sourceSceneDraftId,
            Long modelCallId,
            String finishReason,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            Long elapsedMillis,
            Integer currentAttempt,
            Boolean retryable,
            String errorCode,
            String errorMessage,
            LocalDateTime gmtModified) {
    }

    public record GenerationSceneList(Long generationId, List<GenerationSceneView> scenes) {

        public GenerationSceneList {
            scenes = scenes == null ? List.of() : List.copyOf(scenes);
        }
    }

    public record RetrySceneRequest(Integer expectedAttempt) {
    }
}
