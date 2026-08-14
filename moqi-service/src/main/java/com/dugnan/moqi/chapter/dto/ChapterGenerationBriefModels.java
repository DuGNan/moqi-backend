package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 定义章节正文生成说明只读预览的公开响应契约。
 */
public final class ChapterGenerationBriefModels {

    private ChapterGenerationBriefModels() {
    }

    public record GenerationBriefSourceRef(String sourceType, String sourceId, String contentVersion) {
    }

    public record GenerationBriefPreview(
            Long workId,
            Long chapterId,
            Long chapterPlanVersionId,
            Integer scenePlanNo,
            String templateVersion,
            String sourceValidity,
            List<GenerationBriefSourceRef> sourceRefs,
            String fingerprint,
            LocalDateTime compiledAt,
            String content) {

        public GenerationBriefPreview {
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }
}
