package com.dugnan.moqi.chapter.entitycard;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 将结构化实体卡渲染为正文模型可读且不扩张事实边界的自然语言。
 */
@Component
public class GenerationEntityCardRenderer {

    public String render(GenerationEntityCard card) {
        List<String> facts = new ArrayList<>();
        add(facts, card.confirmedDescription());
        addLabeled(facts, "别名", String.join("、", card.aliases()));
        addLabeled(facts, "所属", card.affiliation());
        addLabeled(facts, "故事作用", card.storyRole());
        addLabeled(facts, "当前状态", card.currentState());
        addLabeled(facts, "角色认知边界", card.characterKnowledge());
        addLabeled(facts, "首次出现说明", card.firstAppearanceExplanation());
        if (card.firstEstablishedInChapter()) {
            facts.add("本章首次建立，正文必须完成必要但有限的首次说明");
        }
        addLabeled(facts, "禁止推断", card.prohibitedInference());
        if (facts.isEmpty()) {
            facts.add("仅确认名称与类型；不得补造背景、能力、关系或当前状态");
        }
        return String.join("；", facts);
    }

    private void add(List<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value.trim());
        }
    }

    private void addLabeled(List<String> values, String label, String value) {
        if (StringUtils.hasText(value)) {
            values.add(label + "：" + value.trim());
        }
    }
}
