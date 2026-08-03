package com.dugnan.moqi.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 验证旧上下文快照缺失权威字段时安全降级为证据。
 */
class StoryContextItemCompatibilityTest {

    @Test
    void readsLegacyItemAsEvidence() throws Exception {
        String json = """
                {
                  "sourceType":"CONVERSATION_TURN",
                  "sourceId":"1:2",
                  "contentVersion":"0:0",
                  "sourceUpdatedAt":null,
                  "messageRole":"USER",
                  "content":"历史讨论",
                  "required":false,
                  "priority":600,
                  "order":400,
                  "originalTokenEstimate":10,
                  "selectedTokenEstimate":10,
                  "selectionReason":"INCLUDED"
                }
                """;

        StoryContextItem item = new ObjectMapper().readValue(json, StoryContextItem.class);

        assertThat(item.authorityStatus()).isEqualTo(StoryContextAuthorityStatus.EVIDENCE);
    }
}
