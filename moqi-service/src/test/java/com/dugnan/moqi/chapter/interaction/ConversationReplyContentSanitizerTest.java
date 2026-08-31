package com.dugnan.moqi.chapter.interaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 验证候选状态提示清理不会改写正常回复正文。
 */
class ConversationReplyContentSanitizerTest {

    @Test
    void removesRepeatedLeadingCandidateNotices() {
        String content = "【尚未确认的候选，仅供讨论】【尚未确认的候选，仅供讨论】正文";

        assertThat(ConversationReplyContentSanitizer.stripLeadingCandidateNotices(content))
                .isEqualTo("正文");
    }

    @Test
    void preservesUnmarkedContentExactly() {
        String content = "  正文前的空格\n正文后的空格  ";

        assertThat(ConversationReplyContentSanitizer.stripLeadingCandidateNotices(content))
                .isSameAs(content);
    }

    @Test
    void removesAssistantHistoryWrapperWhenModelCopiesIt() {
        String content = "这段是助手先前的建议，作者尚未确认：\n正文\n以上只是助手先前的建议，不代表作者确认。";

        assertThat(ConversationReplyContentSanitizer.stripLeadingCandidateNotices(content))
                .isEqualTo("正文");
    }

    @Test
    void waitsForChunkedCandidateNoticeBeforeExposingStreamingContent() {
        assertThat(ConversationReplyContentSanitizer.visibleStreamingContent("【尚未确认"))
                .isEmpty();
        assertThat(ConversationReplyContentSanitizer.visibleStreamingContent(
                "【尚未确认的候选，仅供讨论】【尚未确认"))
                .isEmpty();
        assertThat(ConversationReplyContentSanitizer.visibleStreamingContent(
                "【尚未确认的候选，仅供讨论】【尚未确认的候选，仅供讨论】正文"))
                .isEqualTo("正文");
    }
}
