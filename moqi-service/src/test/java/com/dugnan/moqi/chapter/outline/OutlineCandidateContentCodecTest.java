package com.dugnan.moqi.chapter.outline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Scene;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Beat;
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

    /**
     * 验证 V1 场景大纲读取后只投影为 V2 节拍，不将旧标签写回新契约。
     */
    @Test
    void projectsV1ScenesToVersionTwoBeats() {
        OutlineCandidateContent result = codec.read("""
                {"goal":"目标","coreConflict":"冲突","scenes":[{"id":"beat-1","title":"开端","content":"角色作出选择","tags":["旧标签"]}],"constraints":["限制"]}
                """);

        assertThat(result.schemaVersion()).isEqualTo(2);
        assertThat(result.chapterGoal()).isEqualTo("目标");
        assertThat(result.beats()).containsExactly(new Beat("beat-1", "开端：角色作出选择"));
    }

    /**
     * 验证 V2 节拍键重复会被拒绝。
     */
    @Test
    void rejectsDuplicateBeatKey() {
        OutlineCandidateContent content = new OutlineCandidateContent(2, "目的", null, "目标", "冲突",
                List.of(new Beat("beat-1", "变化一"), new Beat("beat-1", "变化二")), null, null, null, List.of());

        assertThatThrownBy(() -> codec.normalize(content))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OUTLINE_CANDIDATE_INVALID);
    }

    /**
     * 模型偶尔会遗漏 schemaVersion，但只要返回了明确的 V2 字段，就应按 V2 读取，
     * 避免错误降级到 V1 后报告 chapterGoal 为空。
     */
    @Test
    void readsVersionTwoModelOutputWithoutSchemaVersion() {
        OutlineCandidateContent result = codec.read("""
                {
                  "chapterPurpose":"建立主角与机甲的关系",
                  "chapterGoal":"顾临被迫驾驶熟悉型号的机甲逃生",
                  "coreConflict":"机械师的维修经验与零驾驶经验发生冲突",
                  "beats":[{"beatKey":"beat-1","summary":"顾临维护玄武并遭遇突袭"}],
                  "constraints":["顾临不是驾驶员"]
                }
                """);

        assertThat(result.schemaVersion()).isEqualTo(2);
        assertThat(result.chapterGoal()).isEqualTo("顾临被迫驾驶熟悉型号的机甲逃生");
        assertThat(result.beats()).containsExactly(new Beat("beat-1", "顾临维护玄武并遭遇突袭"));
    }

    /**
     * 模型返回的旧格式字段类型错误属于候选内容无效，不应泄漏为内部错误。
     */
    @Test
    void classifiesMalformedLegacyCollectionsAsInvalidCandidate() {
        assertThatThrownBy(() -> codec.read("""
                {
                  "goal":"目标",
                  "coreConflict":"冲突",
                  "scenes":[{"id":"beat-1","title":"开端","content":"发生变化","tags":[]}],
                  "constraints":{"value":"错误类型"}
                }
                """))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OUTLINE_CANDIDATE_INVALID);
    }
}
