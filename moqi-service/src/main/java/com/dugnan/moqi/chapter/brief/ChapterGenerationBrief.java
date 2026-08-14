package com.dugnan.moqi.chapter.brief;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 保存章节正文生成使用的结构化说明、来源版本与稳定指纹。
 */
public record ChapterGenerationBrief(
        int schemaVersion,
        String templateVersion,
        Long workId,
        String workTitle,
        Long chapterId,
        Integer chapterNo,
        String chapterTitle,
        String chapterPurpose,
        String chapterGoal,
        String coreConflict,
        List<String> openingConditions,
        List<String> readerKnowledge,
        List<String> eventCausality,
        List<String> stateChanges,
        List<String> characterConstraints,
        List<EntityExplanation> entityExplanations,
        List<String> requiredEndingState,
        List<String> creativeFreedom,
        List<String> prohibitedInventions,
        List<SourceRef> sourceRefs,
        String fingerprint,
        LocalDateTime compiledAt,
        String content) {

    public ChapterGenerationBrief {
        openingConditions = copy(openingConditions);
        readerKnowledge = copy(readerKnowledge);
        eventCausality = copy(eventCausality);
        stateChanges = copy(stateChanges);
        characterConstraints = copy(characterConstraints);
        entityExplanations = entityExplanations == null ? List.of() : List.copyOf(entityExplanations);
        requiredEndingState = copy(requiredEndingState);
        creativeFreedom = copy(creativeFreedom);
        prohibitedInventions = copy(prohibitedInventions);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /** 已确认实体在当前章节中的最小解释。 */
    public record EntityExplanation(Long sourceId, String type, String name, String explanation) {
    }

    /** 参与本次编译的固定来源。 */
    public record SourceRef(String sourceType, String sourceId, String contentVersion) {
    }
}
