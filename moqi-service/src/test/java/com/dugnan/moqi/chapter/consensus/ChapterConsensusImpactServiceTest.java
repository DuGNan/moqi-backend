package com.dugnan.moqi.chapter.consensus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.ReaderProgress;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.StateChange;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 验证大纲共识影响摘要采用保守且可解释的确定性判断。
 */
class ChapterConsensusImpactServiceTest {

    /**
     * 验证只有大纲显式包含对应共识文本时才标记 preserved。
     */
    @Test
    void marksOnlyExplicitlyCarriedDimensionsAsPreserved() {
        ChapterConsensusValidator validator = new ChapterConsensusValidator();
        ChapterConsensusCodec codec = new ChapterConsensusCodec(new ObjectMapper(), validator);
        ChapterConsensusImpactService service = new ChapterConsensusImpactService(codec);
        String brief = codec.write(new ChapterConsensusContentV1(
                1,
                "推进主角选择",
                new StateChange("犹豫", "决断"),
                "承担代价",
                new ReaderProgress("兑现救人回报", "谁泄露了情报"),
                List.of(),
                List.of()));

        var result = service.assess(
                brief,
                "本章推进主角选择，人物从犹豫走向决断，但读者线索仍待细化。");

        assertThat(result.chapterTask().status()).isEqualTo("preserved");
        assertThat(result.stateChange().status()).isEqualTo("preserved");
        assertThat(result.readerProgress().status()).isEqualTo("possibly_changed");
        assertThat(result.readerProgress().reason()).isNotBlank();
    }
}
