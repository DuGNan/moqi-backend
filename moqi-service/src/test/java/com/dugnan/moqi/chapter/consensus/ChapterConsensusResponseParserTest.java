package com.dugnan.moqi.chapter.consensus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * @author dgn
 * @date 2026-08-02
 * @description 验证章节共识 Provider JSON 的字段与类型契约。
 */
class ChapterConsensusResponseParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ChapterConsensusResponseParser parser =
            new ChapterConsensusResponseParser(objectMapper);

    /**
     * 验证完整 JSON 可转换为持久化契约。
     *
     * @throws Exception JSON 构造失败
     */
    @Test
    void parsesCompleteContract() throws Exception {
        ChapterConsensusContentV1 content = parser.parse(objectMapper.readTree(validJson()));

        assertThat(content.schemaVersion()).isEqualTo(1);
        assertThat(content.stateChange().to()).isEqualTo("决断");
        assertThat(content.decisions()).hasSize(1);
    }

    /**
     * 验证缺失嵌套对象时按 JSON 契约错误拒绝。
     *
     * @throws Exception JSON 构造失败
     */
    @Test
    void rejectsMissingRequiredField() throws Exception {
        var root = objectMapper.readTree(validJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove("stateChange");

        assertThatThrownBy(() -> parser.parse(root))
                .isInstanceOf(ChapterConsensusJsonException.class)
                .hasMessage("模型共识 JSON 不符合字段契约");
    }

    /**
     * 验证 decision 状态仍交由业务校验器分类。
     *
     * @throws Exception JSON 构造失败
     */
    @Test
    void leavesDecisionStatusToBusinessValidation() throws Exception {
        var root = objectMapper.readTree(validJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) root
                .get("decisions").get(0)).put("status", "resolved");

        ChapterConsensusContentV1 content = parser.parse(root);

        assertThat(content.decisions().get(0).status()).isEqualTo("resolved");
    }

    private String validJson() {
        return """
                {
                  "schemaVersion": 1,
                  "chapterTask": "推进选择",
                  "stateChange": {"from": "犹豫", "to": "决断"},
                  "keyPush": "承担代价",
                  "readerProgress": {"payoff": "兑现", "openQuestion": "谁泄密"},
                  "writingBoundaries": [],
                  "decisions": [{
                    "key": "protagonist_choice",
                    "title": "主角选择",
                    "status": "pending",
                    "required": true,
                    "prompt": "救人还是追击",
                    "candidateSummary": "",
                    "sourceMessageIds": [11]
                  }]
                }
                """;
    }
}
