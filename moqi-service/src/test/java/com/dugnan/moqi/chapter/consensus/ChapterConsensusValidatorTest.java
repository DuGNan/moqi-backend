package com.dugnan.moqi.chapter.consensus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.Decision;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.ReaderProgress;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.StateChange;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 验证章节结构化共识的规范化和确认约束。
 */
class ChapterConsensusValidatorTest {

    private final ChapterConsensusValidator validator = new ChapterConsensusValidator();

    /**
     * 验证草稿校验会清理文本并对边界与来源 ID 去重。
     */
    @Test
    void normalizesDraftAndRemovesDuplicates() {
        ChapterConsensusContentV1 normalized = validator.normalizeDraft(new ChapterConsensusContentV1(
                1,
                " 推进主角选择 ",
                new StateChange(" 犹豫 ", " 决断 "),
                " 主角承担代价 ",
                new ReaderProgress(" 得到阶段反馈 ", " 谁泄露了情报 "),
                List.of(" 不改变时间线 ", "不改变时间线"),
                List.of(new Decision(
                        "protagonist_choice",
                        " 主角选择 ",
                        "confirmed",
                        true,
                        " 选择救人还是追击 ",
                        " 先救人 ",
                        List.of(11L, 11L, 12L)))));

        assertThat(normalized.chapterTask()).isEqualTo("推进主角选择");
        assertThat(normalized.writingBoundaries()).containsExactly("不改变时间线");
        assertThat(normalized.decisions().get(0).sourceMessageIds()).containsExactly(11L, 12L);
    }

    /**
     * 验证同一共识不允许重复 decision key。
     */
    @Test
    void rejectsDuplicateDecisionKeys() {
        Decision decision = new Decision(
                "protagonist_choice",
                "主角选择",
                "pending",
                true,
                "需要选择",
                "",
                List.of());
        ChapterConsensusContentV1 content = content(List.of(decision, decision));

        assertThatThrownBy(() -> validator.normalizeDraft(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_CONSENSUS_INVALID);
    }

    /**
     * 验证必要待决未确认时不能确认 Brief。
     */
    @Test
    void blocksConfirmationWhenRequiredDecisionIsPending() {
        ChapterConsensusContentV1 content = content(List.of(new Decision(
                "protagonist_choice",
                "主角选择",
                "pending",
                true,
                "需要选择",
                "",
                List.of())));

        assertThatThrownBy(() -> validator.requireConfirmable(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_BRIEF_CONFIRMATION_BLOCKED);
    }

    /**
     * 验证模型生成草稿不能缺少结构化对象和数组。
     */
    @Test
    void rejectsIncompleteGeneratedDraft() {
        ChapterConsensusContentV1 incomplete = new ChapterConsensusContentV1(
                1,
                "推进主角选择",
                null,
                "主角承担代价",
                null,
                null,
                null);

        assertThatThrownBy(() -> validator.normalizeGeneratedDraft(incomplete))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_CONSENSUS_INVALID);
    }

    /**
     * 验证模型生成草稿拒绝非法 decision 状态。
     */
    @Test
    void rejectsUnsupportedGeneratedDecisionStatus() {
        ChapterConsensusContentV1 invalid = content(List.of(new Decision(
                "protagonist_choice",
                "主角选择",
                "resolved",
                true,
                "需要选择",
                "先救人",
                List.of(11L))));

        assertThatThrownBy(() -> validator.normalizeGeneratedDraft(invalid))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_CONSENSUS_INVALID);
    }

    /**
     * 构造测试用共识。
     *
     * @param decisions 待决列表
     * @return 结构化共识
     */
    private ChapterConsensusContentV1 content(List<Decision> decisions) {
        return new ChapterConsensusContentV1(
                1,
                "推进主角选择",
                new StateChange("犹豫", "决断"),
                "主角承担代价",
                new ReaderProgress("得到阶段反馈", "谁泄露了情报"),
                List.of("不改变时间线"),
                decisions);
    }
}
