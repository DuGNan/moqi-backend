package com.dugnan.moqi.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConservativeTokenEstimatorTest {

    private final ConservativeTokenEstimator estimator = new ConservativeTokenEstimator();

    @Test
    void estimatesMixedChineseAndAsciiDeterministically() {
        assertThat(estimator.estimate("abcd 中文")) .isEqualTo(4);
        assertThat(estimator.estimate("abcdefgh")).isEqualTo(2);
    }

    @Test
    void truncatesLongTextWithVisibleMarker() {
        String result = estimator.truncate(
                "这是一个很长的章节正文，用于验证上下文预算裁剪行为。这里还要继续补充更多内容，确保触发裁剪。", 30);

        assertThat(result).contains("内容已按上下文预算裁剪");
        assertThat(estimator.estimate(result)).isLessThanOrEqualTo(30);
    }
}
