package com.dugnan.moqi.chapter.generator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.generator.ChapterContentGenerator.GenerationInput;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 验证本地章节正文生成器的确定性与可读性。
 */
class DeterministicChapterContentGeneratorTest {

    /**
     * 验证相同上下文生成相同且包含关键依据的中文预览。
     */
    @Test
    void generatesDeterministicReadableChinesePreview() {
        DeterministicChapterContentGenerator generator = new DeterministicChapterContentGenerator();
        GenerationInput input = new GenerationInput(
                "玻璃钟表馆",
                "房间终于回答了",
                "姚宁第一次改写自己的判断",
                "{\"goal\":\"隐藏房间回应林风\"}",
                "full_draft",
                "about_3000",
                null,
                "让姚宁的反应更克制");

        String first = generator.generate(input);
        String second = generator.generate(input);

        assertThat(first).isEqualTo(second);
        assertThat(first)
                .contains("房间终于回答了")
                .contains("姚宁第一次改写自己的判断")
                .contains("隐藏房间回应林风")
                .contains("完整草稿")
                .contains("约三千字")
                .contains("让姚宁的反应更克制");
    }
}
