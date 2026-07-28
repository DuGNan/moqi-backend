package com.dugnan.moqi.chapter.consensus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.Decision;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.ReaderProgress;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.StateChange;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 验证章节结构化共识的 JSON 与历史文本兼容行为。
 */
class ChapterConsensusCodecTest {

    private final ChapterConsensusCodec codec =
            new ChapterConsensusCodec(new ObjectMapper(), new ChapterConsensusValidator());

    /**
     * 验证 V1 共识可以稳定序列化并按结构化格式读取。
     */
    @Test
    void writesAndReadsStructuredConsensus() {
        String json = codec.write(content());

        ChapterConsensusDocument document = codec.read(json);

        assertThat(document.contentFormat()).isEqualTo("structured_v1");
        assertThat(document.consensus()).isNotNull();
        assertThat(document.consensus().decisions()).hasSize(1);
        assertThat(document.legacyText()).isNull();
    }

    /**
     * 验证历史自由文本不会被伪造成结构化共识。
     */
    @Test
    void readsHistoricalTextAsLegacy() {
        ChapterConsensusDocument document = codec.read("本章让主角在雨夜完成第一次选择。");

        assertThat(document.contentFormat()).isEqualTo("legacy_text");
        assertThat(document.consensus()).isNull();
        assertThat(document.legacyText()).isEqualTo("本章让主角在雨夜完成第一次选择。");
    }

    /**
     * 验证新写入的结构化共识不能超过序列化大小上限。
     */
    @Test
    void rejectsOversizedStructuredConsensus() {
        ChapterConsensusContentV1 oversized = new ChapterConsensusContentV1(
                1,
                "任务".repeat(40000),
                new StateChange("", ""),
                "",
                new ReaderProgress("", ""),
                List.of(),
                List.of());

        assertThatThrownBy(() -> codec.write(oversized))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_CONSENSUS_INVALID);
    }

    /**
     * 构造测试用结构化共识。
     *
     * @return 结构化共识
     */
    private ChapterConsensusContentV1 content() {
        return new ChapterConsensusContentV1(
                1,
                "推进主角选择",
                new StateChange("犹豫", "决断"),
                "主角承担代价",
                new ReaderProgress("得到阶段反馈", "谁泄露了情报"),
                List.of("不改变时间线"),
                List.of(new Decision(
                        "protagonist_choice",
                        "主角选择",
                        "confirmed",
                        true,
                        "选择救人还是追击",
                        "先救人",
                        List.of(11L))));
    }
}
