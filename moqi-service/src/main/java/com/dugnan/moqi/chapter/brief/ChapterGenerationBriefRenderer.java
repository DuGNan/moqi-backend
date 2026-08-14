package com.dugnan.moqi.chapter.brief;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief.EntityExplanation;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 将结构化章节正文生成说明渲染为带优先级和解释的人类可读指令。
 */
@Component
public class ChapterGenerationBriefRenderer {

    public String render(ChapterGenerationBrief brief) {
        StringBuilder text = new StringBuilder();
        text.append("# Chapter Generation Brief\n\n");
        text.append("## P0｜章节身份与任务\n");
        line(text, "作品", brief.workTitle());
        line(text, "章节", "第" + brief.chapterNo() + "章｜" + brief.chapterTitle());
        line(text, "章节作用", brief.chapterPurpose());
        line(text, "章节目标", brief.chapterGoal());
        line(text, "核心冲突", brief.coreConflict());
        section(text, "## P0｜开场条件", brief.openingConditions());
        section(text, "## P0｜读者必须知道", brief.readerKnowledge());
        section(text, "## P0｜事件因果与推进", brief.eventCausality());
        section(text, "## P0｜必须发生的状态变化", brief.stateChanges());
        section(text, "## P0｜人物目标与认知边界", brief.characterConstraints());
        entitySection(text, brief.entityExplanations());
        section(text, "## P0｜结尾必须达到", brief.requiredEndingState());
        section(text, "## P1｜可自由发挥", brief.creativeFreedom());
        section(text, "## P0｜禁止发明或改写", brief.prohibitedInventions());
        return text.toString().stripTrailing();
    }

    private void entitySection(StringBuilder text, List<EntityExplanation> entities) {
        text.append("\n## P0｜相关实体说明\n");
        if (entities.isEmpty()) {
            text.append("- 无已确认实体资料；只使用规划中明确给出的名称和事实。\n");
            return;
        }
        for (EntityExplanation entity : entities) {
            text.append("- ").append(entity.name()).append("（").append(entity.type()).append("）：")
                    .append(entity.explanation()).append('\n');
        }
    }

    private void section(StringBuilder text, String title, List<String> values) {
        text.append('\n').append(title).append('\n');
        if (values.isEmpty()) {
            text.append("- 未提供；不得自行补造事实。\n");
            return;
        }
        values.forEach(value -> text.append("- ").append(value).append('\n'));
    }

    private void line(StringBuilder text, String label, String value) {
        text.append("- ").append(label).append("：")
                .append(StringUtils.hasText(value) ? value : "未提供；不得自行补造").append('\n');
    }
}
