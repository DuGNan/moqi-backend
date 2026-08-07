package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author dgn
 * @date 2026-08-07
 * @description 定义章节生成策略隔离实验的请求和只读结果契约。
 */
public final class ChapterGenerationExperimentModels {

    private ChapterGenerationExperimentModels() {
    }

    public record RunExperimentRequest(
            String experimentGroupKey,
            String strategy,
            Integer sampleNo,
            Integer targetWordCount,
            Double temperature,
            String storyIntent) {
    }

    public record ExperimentView(
            Long id,
            Long workId,
            Long chapterId,
            Long chapterPlanVersionId,
            String experimentGroupKey,
            String strategy,
            Integer sampleNo,
            String experimentStatus,
            String templateVersion,
            String inputFingerprint,
            String provider,
            String model,
            Integer configVersion,
            Integer credentialVersion,
            String sceneRouteJson,
            List<Long> modelCallIds,
            String rawSceneOutputsJson,
            String generatedContent,
            Integer wordCount,
            Long elapsedMillis,
            String errorMessage,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record ExperimentList(List<ExperimentView> experiments) {
    }
}
