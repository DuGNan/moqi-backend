package com.dugnan.moqi.chapter.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * @author dgn
 * @date 2026-10-25
 * @description 验证多处修改原子定位、空稿新增和重复或交叠原文保护。
 */
class ProseConversationProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void appliesDisjointChangesWithoutTouchingSurroundingWhitespace() throws Exception {
        var edits = mapper.readTree("[{\"original\":\"甲段\",\"replacement\":\"新甲\"},"
                + "{\"original\":\"乙段\",\"replacement\":\"新乙\"}]");
        assertThat(ProseConversationProtocol.apply("  前文\n甲段\n中间\n乙段\n末尾  ", edits))
                .isEqualTo("  前文\n新甲\n中间\n新乙\n末尾  ");
    }

    @Test
    void rejectsAmbiguousMissingAndOverlappingOriginals() throws Exception {
        var edit = mapper.readTree("[{\"original\":\"甲\",\"replacement\":\"乙\"}]");
        assertThatThrownBy(() -> ProseConversationProtocol.apply("甲甲", edit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProseConversationProtocol.apply("丙", edit)).isInstanceOf(IllegalArgumentException.class);
        var overlap = mapper.readTree("[{\"original\":\"甲乙\",\"replacement\":\"新\"},"
                + "{\"original\":\"乙丙\",\"replacement\":\"新\"}]");
        assertThatThrownBy(() -> ProseConversationProtocol.apply("甲乙丙", overlap)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAllowsEmptyOriginalForAnEmptyEditor() throws Exception {
        var edits = mapper.readTree("[{\"original\":\"\",\"replacement\":\"新文\"}]");
        assertThat(ProseConversationProtocol.apply("", edits)).isEqualTo("新文");
        assertThatThrownBy(() -> ProseConversationProtocol.apply("已有正文", edits)).isInstanceOf(IllegalArgumentException.class);
    }
}
