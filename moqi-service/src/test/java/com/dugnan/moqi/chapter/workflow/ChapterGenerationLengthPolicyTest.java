package com.dugnan.moqi.chapter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy.SceneWordRange;

/**
 * @author dgn
 * @date 2026-08-09
 * @description 验证章节字数策略与 Prompt 编译保持既有语义。
 */
class ChapterGenerationLengthPolicyTest {

    private final ChapterGenerationLengthPolicy lengthPolicy = new ChapterGenerationLengthPolicy();
    private final ChapterGenerationPromptCompiler promptCompiler =
            new ChapterGenerationPromptCompiler(null, null, null, null, null);

    @Test
    void resolvesPresetAndDistributesTheChapterTargetAcrossAllScenes() {
        assertThat(lengthPolicy.resolveTargetWordCount("about_3000", null)).isEqualTo(3000);

        SceneWordRange first = lengthPolicy.sceneWordRange(3000, 7, 1);
        SceneWordRange last = lengthPolicy.sceneWordRange(3000, 7, 7);

        assertThat(first).isEqualTo(new SceneWordRange(386, 429, 472));
        assertThat(last).isEqualTo(new SceneWordRange(385, 428, 471));
        assertThat(promptCompiler.generationInstruction(first))
                .contains("严格控制在 386 至 472 个中文字符", "建议约 429 个中文字符");
        assertThat(promptCompiler.contextBuildCommand(1L, 2L, 16384, 4096, null, first).currentInput())
                .contains("严格控制在 386 至 472 个中文字符", "建议约 429 个中文字符");
        assertThat(first.contains(386)).isTrue();
        assertThat(first.contains(472)).isTrue();
        assertThat(first.contains(385)).isFalse();
        assertThat(promptCompiler.correctionInstruction(first, 300))
                .contains("扩写", "386 至 472 个中文字符", "只输出修订后的完整正文");
        assertThat(promptCompiler.correctionInstruction(first, 500))
                .contains("压缩", "386 至 472 个中文字符");
    }

    @Test
    void derivesAProviderLimitFromTheWordRangeAndHonorsProviderCapabilities() {
        assertThat(lengthPolicy.maxOutputTokens(472, null)).isEqualTo(472);
        assertThat(lengthPolicy.maxOutputTokens(472, 300)).isEqualTo(300);
    }

    @Test
    void rejectsUnsupportedOrOutOfRangeCustomTargets() {
        assertThatThrownBy(() -> lengthPolicy.resolveTargetWordCount("unknown", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lengthPolicy.resolveTargetWordCount("custom", 300))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
