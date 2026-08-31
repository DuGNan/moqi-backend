package com.dugnan.moqi.chapter.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageInteraction;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageInteractionOption;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageInteractionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author dgn
 * @date 2026-08-31
 * @description 验证章节讨论语义草稿、服务端交互协议和安全降级边界。
 */
class DiscussionInteractionCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesValidSingleChoiceEnvelope() {
        String json = """
                {"content":"请选择或描述自己的方向。","interaction":{"schemaVersion":1,
                "type":"single_choice","questionId":"q-1","question":"觉醒代价是什么？","allowCustom":true,
                "options":[{"optionId":"a","title":"记忆","description":"失去记忆","tradeoffs":"信息控制"},
                {"optionId":"b","title":"疼痛","description":"承受疼痛","tradeoffs":"长期性较弱"}]}}
                """;

        var result = DiscussionInteractionCodec.parseAssistantEnvelope(json, mapper);

        assertThat(result.content()).isEqualTo("请选择或描述自己的方向。");
        assertThat(result.interaction()).isNotNull();
        assertThat(result.interactionJson()).contains("single_choice");
    }

    @Test
    void convertsNaturalComparisonDraftToServerOwnedInteractionContract() {
        String json = """
                {"回复":"两个方向的核心差别在主角承担的代价。","问题":"你更倾向哪个方向？",
                "选项":[{"标题":"公开真相","说明":"立即推动冲突","取舍":"风险更快暴露"},
                {"标题":"暂时隐瞒","说明":"保留调查空间","取舍":"关系压力会累积"}]}
                """;

        var result = DiscussionInteractionCodec.parseComparisonDraft(json, mapper, "task-88", 2);

        assertThat(result.content()).isEqualTo("两个方向的核心差别在主角承担的代价。");
        assertThat(result.interaction()).isEqualTo(new MessageInteraction(
                1, "single_choice", "task-88", "你更倾向哪个方向？", List.of(
                        new MessageInteractionOption("option-1", "公开真相", "立即推动冲突", "风险更快暴露"),
                        new MessageInteractionOption("option-2", "暂时隐瞒", "保留调查空间", "关系压力会累积")),
                true));
        assertThat(result.interactionJson()).contains("schemaVersion", "single_choice", "option-1");
    }

    @Test
    void convertsNaturalClarificationDraftToServerOwnedInteractionContract() {
        String json = """
                {"回复":"我还不能确定你指的是哪一项。","问题":"具体指的是哪一项？"}
                """;

        var result = DiscussionInteractionCodec.parseClarificationDraft(json, mapper, "task-89");

        assertThat(result.content()).isEqualTo("我还不能确定你指的是哪一项。");
        assertThat(result.interaction()).isEqualTo(new MessageInteraction(
                1, "open_question", "task-89", "具体指的是哪一项？", List.of(), true));
        assertThat(result.interactionJson()).contains("schemaVersion", "open_question", "task-89");
    }

    @Test
    void convertsComparisonWithoutObjectsToClarification() {
        String json = """
                {"回复":"先说明两个方向的核心差别。","问题":"选择哪个？","选项":[]}
                """;

        var result = DiscussionInteractionCodec.parseComparisonDraft(json, mapper, "task-90", 2);

        assertThat(result.content()).isEqualTo("先说明两个方向的核心差别。");
        assertThat(result.interaction()).isNotNull();
        assertThat(result.interaction().type()).isEqualTo("open_question");
        assertThat(result.interaction().question()).isEqualTo("选择哪个？");
    }

    @Test
    void preservesAllReadableSemanticsWhenOptionCountExceedsControlLimit() {
        String json = """
                {"回复":"完整比较六个方向。","问题":"你倾向哪个？","选项":[
                {"标题":"一","说明":"说明一","取舍":"取舍一"},
                {"标题":"二","说明":"说明二","取舍":"取舍二"},
                {"标题":"三","说明":"说明三","取舍":"取舍三"},
                {"标题":"四","说明":"说明四","取舍":"取舍四"},
                {"标题":"五","说明":"说明五","取舍":"取舍五"},
                {"标题":"六","说明":"说明六","取舍":"取舍六"}]}
                """;

        var result = DiscussionInteractionCodec.parseComparisonDraft(json, mapper, "task-91");

        assertThat(result.interaction()).isNull();
        assertThat(result.degradationReason()).isEqualTo("option_count_out_of_range");
        assertThat(result.content())
                .contains("完整比较六个方向。", "你倾向哪个？", "1. 一", "说明一", "取舍：取舍一")
                .contains("6. 六", "说明六", "取舍：取舍六");
    }

    @Test
    void failsInsteadOfExposingUnrecoverableRawJson() {
        assertThatThrownBy(() -> DiscussionInteractionCodec.parseComparisonDraft(
                "{\"回复\":", mapper, "task-92"))
                .isInstanceOf(DiscussionInteractionCodec.StructuredOutputException.class)
                .hasMessageContaining("无法解析");
    }

    @Test
    void degradesReadableNonJsonResponseWithoutRetry() {
        var result = DiscussionInteractionCodec.parseComparisonDraft(
                "两个方向的差别是风险出现的时机。", mapper, "task-93");

        assertThat(result.content()).isEqualTo("两个方向的差别是风险出现的时机。");
        assertThat(result.interaction()).isNull();
        assertThat(result.degradationReason()).isEqualTo("non_json_readable_text");
    }

    @Test
    void degradesInvalidModelStructureToPlainText() {
        String json = """
                {"content":"先聊聊你在意的代价。","interaction":{"schemaVersion":1,
                "type":"single_choice","questionId":"q-1","question":"代价？","allowCustom":true,"options":[]}}
                """;

        var result = DiscussionInteractionCodec.parseAssistantEnvelope(json, mapper);

        assertThat(result.interaction()).isNull();
        assertThat(result.interactionJson()).isNull();
        assertThat(result.content()).contains("先聊聊你在意的代价");
    }

    @Test
    void validatesOptionOwnershipAndBuildsReadableUserMessage() {
        MessageInteraction interaction = new MessageInteraction(1, "single_choice", "q-1", "代价？", List.of(
                new MessageInteractionOption("a", "记忆", "", ""),
                new MessageInteractionOption("b", "疼痛", "", "")), true);

        var result = DiscussionInteractionCodec.validateResponse(
                interaction, new MessageInteractionResponse(1, "q-1", "a", "保留人物面孔"));

        assertThat(result.content()).isEqualTo("我选择“记忆”。补充：保留人物面孔");
        assertThatThrownBy(() -> DiscussionInteractionCodec.validateResponse(
                interaction, new MessageInteractionResponse(1, "q-1", "forged", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于来源问题");
    }
}
