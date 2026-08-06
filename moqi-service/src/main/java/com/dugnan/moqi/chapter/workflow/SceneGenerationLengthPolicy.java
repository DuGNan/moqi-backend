package com.dugnan.moqi.chapter.workflow;

import java.util.Map;

/**
 * @author dgn
 * @date 2026-08-06
 * @description 将作者选择的章节目标字数稳定拆分为逐场景字数区间和模型输出上限。
 */
public final class SceneGenerationLengthPolicy {

    public static final String DEFAULT_PRESET = "about_3000";
    private static final String CUSTOM_PRESET = "custom";
    private static final int MIN_CUSTOM_WORD_COUNT = 500;
    private static final int MAX_CUSTOM_WORD_COUNT = 20000;
    private static final int MIN_OUTPUT_TOKENS = 128;
    private static final double LOWER_BOUND_RATIO = 0.9D;
    private static final double UPPER_BOUND_RATIO = 1.1D;
    private static final Map<String, Integer> PRESET_TARGETS = Map.of(
            "about_1500", 1500,
            DEFAULT_PRESET, 3000,
            "about_5000", 5000);

    private SceneGenerationLengthPolicy() {
    }

    public static Integer resolveTargetWordCount(String lengthPreset, Integer customWordCount) {
        String normalizedPreset = normalizePreset(lengthPreset);
        if (CUSTOM_PRESET.equals(normalizedPreset)) {
            if (customWordCount == null
                    || customWordCount < MIN_CUSTOM_WORD_COUNT
                    || customWordCount > MAX_CUSTOM_WORD_COUNT) {
                throw new IllegalArgumentException("customWordCount 必须在 500 到 20000 之间");
            }
            return customWordCount;
        }
        Integer target = PRESET_TARGETS.get(normalizedPreset);
        if (target == null) {
            throw new IllegalArgumentException("lengthPreset 不支持");
        }
        return target;
    }

    public static String normalizePreset(String lengthPreset) {
        return lengthPreset == null || lengthPreset.isBlank() ? DEFAULT_PRESET : lengthPreset.trim();
    }

    public static SceneWordRange sceneWordRange(int totalWordCount, int sceneCount, int sequenceNo) {
        if (totalWordCount <= 0 || sceneCount <= 0 || sequenceNo <= 0 || sequenceNo > sceneCount) {
            throw new IllegalArgumentException("章节目标字数、场景数量和场景序号必须为有效正整数");
        }
        int baseTarget = totalWordCount / sceneCount;
        int remainder = totalWordCount % sceneCount;
        int target = baseTarget + (sequenceNo <= remainder ? 1 : 0);
        int minimum = (int) Math.floor(target * LOWER_BOUND_RATIO);
        int maximum = (int) Math.ceil(target * UPPER_BOUND_RATIO);
        return new SceneWordRange(minimum, target, maximum);
    }

    public static int maxOutputTokens(int maximumWordCount, Integer providerMaximum) {
        int calculated = Math.max(MIN_OUTPUT_TOKENS, maximumWordCount);
        return providerMaximum == null ? calculated : Math.min(calculated, providerMaximum);
    }

    public record SceneWordRange(int minimum, int target, int maximum) {

        public boolean contains(int wordCount) {
            return wordCount >= minimum && wordCount <= maximum;
        }
    }
}
