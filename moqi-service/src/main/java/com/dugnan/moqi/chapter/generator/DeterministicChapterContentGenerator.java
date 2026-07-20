package com.dugnan.moqi.chapter.generator;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 根据章节上下文生成确定性的本地中文正文预览。
 */
@Component
public class DeterministicChapterContentGenerator implements ChapterContentGenerator {

    @Override
    public String generate(GenerationInput input) {
        String chapterTitle = text(input.chapterTitle(), "未命名章节");
        String brief = text(input.briefContent(), "人物在新的局面中作出选择");
        String outline = text(input.outlineContent(), "按既定章节目标推进情节");
        String mode = "segmented_draft".equals(input.generationMode()) ? "分段草稿" : "完整草稿";
        String length = "custom".equals(input.lengthPreset()) && input.customWordCount() != null
                ? "约" + input.customWordCount() + "字"
                : "约三千字";
        String feedback = StringUtils.hasText(input.feedback())
                ? "\n\n重写时，叙述进一步遵循这条反馈：" + input.feedback().trim() + "。"
                : "";
        return "《" + chapterTitle + "》\n\n"
                + "这是一版按" + mode + "方式、以" + length + "展开密度构造的正文预览。\n\n"
                + "这一章从一个仍未被说破的变化开始。" + brief + "。"
                + "人物没有立刻给出答案，而是在环境的细节与彼此的停顿中重新衡量眼前的局面。\n\n"
                + "章节沿着大纲依据继续展开：" + outline + "。"
                + "冲突因此向前推进，选择也留下了能够衔接下一段情节的余波。"
                + feedback;
    }

    private String text(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
