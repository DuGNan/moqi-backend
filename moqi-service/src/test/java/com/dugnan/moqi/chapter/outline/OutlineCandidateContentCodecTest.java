package com.dugnan.moqi.chapter.outline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Scene;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 验证候选内容的结构、非空和场景稳定标识约束。
 */
class OutlineCandidateContentCodecTest {

    private final OutlineCandidateContentCodec codec = new OutlineCandidateContentCodec(new ObjectMapper());

    /**
     * 验证重复场景标识会被拒绝，避免差异计算歧义。
     */
    @Test
    void rejectsDuplicateSceneId() {
        OutlineCandidateContent content = new OutlineCandidateContent("目标", "冲突", List.of(
                new Scene("scene-1", "场景一", "内容一", List.of()),
                new Scene("scene-1", "场景二", "内容二", List.of())), List.of());

        assertThatThrownBy(() -> codec.normalize(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OUTLINE_CANDIDATE_INVALID);
    }

    /**
     * 验证内容序列化后可稳定回读。
     */
    @Test
    void normalizesAndReadsStructuredContent() {
        OutlineCandidateContent content = new OutlineCandidateContent(" 目标 ", " 冲突 ",
                List.of(new Scene(" scene-1 ", " 标题 ", " 内容 ", List.of(" 标签 "))), List.of(" 约束 "));

        OutlineCandidateContent result = codec.read(codec.write(content));

        assertThat(result.goal()).isEqualTo("目标");
        assertThat(result.scenes().get(0).id()).isEqualTo("scene-1");
        assertThat(result.constraints()).containsExactly("约束");
    }
}
