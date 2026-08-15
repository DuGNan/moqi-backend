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
            new ChapterGenerationPromptCompiler(null, null, null, null);

    @Test
    void resolvesPresetAndUsesAChapterLevelSoftRangeWithoutSceneDivision() {
        assertThat(lengthPolicy.resolveTargetWordCount("about_3000", null)).isEqualTo(3000);
        assertThat(lengthPolicy.resolveTargetWordCount(null, null)).isEqualTo(3000);

        SceneWordRange first = lengthPolicy.sceneSoftRange(3000);
        SceneWordRange last = lengthPolicy.sceneSoftRange(3000);

        assertThat(first).isEqualTo(new SceneWordRange(2700, 3000, 3301));
        assertThat(last).isEqualTo(first);
        assertThat(promptCompiler.generationInstruction(first))
                .contains("整章目标篇幅为软区间", "不得把整章目标平均摊到每个场景")
                .doesNotContain("严格控制");
        assertThat(promptCompiler.contextBuildCommand(1L, 2L, 16384, 4096, null, first).currentInput())
                .contains("整章目标篇幅为软区间", "不得把整章目标平均摊到每个场景");
    }

    @Test
    void derivesAProviderLimitFromTheWordRangeAndHonorsProviderCapabilities() {
        assertThat(lengthPolicy.maxOutputTokens(3301, null)).isEqualTo(3301);
        assertThat(lengthPolicy.maxOutputTokens(3301, 3000)).isEqualTo(3000);
    }

    @Test
    void rejectsUnsupportedOrOutOfRangeCustomTargets() {
        assertThatThrownBy(() -> lengthPolicy.resolveTargetWordCount("unknown", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lengthPolicy.resolveTargetWordCount("custom", 300))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
