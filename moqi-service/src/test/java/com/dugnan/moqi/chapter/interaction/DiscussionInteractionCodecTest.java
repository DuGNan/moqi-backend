package com.dugnan.moqi.chapter.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageInteraction;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageInteractionOption;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageInteractionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

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
