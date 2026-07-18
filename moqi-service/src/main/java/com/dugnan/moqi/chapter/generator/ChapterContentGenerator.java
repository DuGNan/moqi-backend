package com.dugnan.moqi.chapter.generator;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 定义可替换的章节正文生成边界。
 */
public interface ChapterContentGenerator {

    /**
     * 根据固化的章节上下文生成正文预览。
     *
     * @param input 生成输入
     * @return 生成正文
     */
    String generate(GenerationInput input);

    public record GenerationInput(
            String workTitle,
            String chapterTitle,
            String briefContent,
            String outlineContent,
            String generationMode,
            String lengthPreset,
            Integer customWordCount,
            String feedback) {
    }
}
