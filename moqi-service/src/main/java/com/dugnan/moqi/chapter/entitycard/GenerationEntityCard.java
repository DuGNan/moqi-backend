package com.dugnan.moqi.chapter.entitycard;

import java.util.List;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 保存章节生成可使用的已确认实体知识及其认知和推断边界。
 */
public record GenerationEntityCard(
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

    public GenerationEntityCard {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
