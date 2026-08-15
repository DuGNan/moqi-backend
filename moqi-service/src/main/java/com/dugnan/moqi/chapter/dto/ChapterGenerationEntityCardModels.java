package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefSourceRef;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 定义章节生成相关实体卡只读预览的公开响应契约。
 */
public final class ChapterGenerationEntityCardModels {

    private ChapterGenerationEntityCardModels() {
    }

    public record EntityCardView(
            Long entityId,
            String type,
            String name,
            List<String> aliases,
            String affiliation,
            String storyRole,
            String currentState,
            String characterKnowledge,
            String firstAppearanceExplanation,
            String prohibitedInference,
            boolean firstEstablishedInChapter,
            String confirmedDescription,
            String sourceVersion) {

        public EntityCardView {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    public record EntityCardPreview(
            Long workId,
            Long chapterId,
            Long chapterPlanVersionId,
            Integer scenePlanNo,
            String templateVersion,
            String sourceValidity,
            List<GenerationBriefSourceRef> sourceRefs,
            String fingerprint,
            LocalDateTime compiledAt,
            List<EntityCardView> cards) {

        public EntityCardPreview {
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
            cards = cards == null ? List.of() : List.copyOf(cards);
        }
    }
}
