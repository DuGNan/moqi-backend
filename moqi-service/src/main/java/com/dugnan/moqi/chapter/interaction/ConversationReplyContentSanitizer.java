package com.dugnan.moqi.chapter.interaction;

import java.util.List;

/**
 * @author dgn
 * @date 2026-08-26
 * @description 清理不应进入作者可见正文或后续模型历史的内部状态提示。
 */
public final class ConversationReplyContentSanitizer {

    private static final String CANDIDATE_NOTICE = "【尚未确认的候选，仅供讨论】";
    private static final String ASSISTANT_HISTORY_NOTICE = "这段是助手先前的建议，作者尚未确认：";
    private static final String ASSISTANT_HISTORY_END = "以上只是助手先前的建议，不代表作者确认。";
    private static final List<String> LEADING_NOTICES = List.of(CANDIDATE_NOTICE, ASSISTANT_HISTORY_NOTICE);

    private ConversationReplyContentSanitizer() {
    }

    /**
     * 删除模型模仿出来的一个或多个开头状态提示，正文中的同名文字保持不变。
     *
     * @param content 回复正文
     * @return 清理后的正文
     */
    public static String stripLeadingCandidateNotices(String content) {
        if (content == null) {
            return null;
        }
        String result = content.stripLeading();
        if (LEADING_NOTICES.stream().noneMatch(result::startsWith)) {
            return content;
        }
        boolean stripped;
        do {
            stripped = false;
            for (String notice : LEADING_NOTICES) {
                if (result.startsWith(notice)) {
                    result = result.substring(notice.length()).stripLeading();
                    stripped = true;
                }
            }
        } while (stripped);
        if (result.endsWith(ASSISTANT_HISTORY_END)) {
            result = result.substring(0, result.length() - ASSISTANT_HISTORY_END.length()).stripTrailing();
        }
        return result;
    }

    /**
     * 返回当前可以安全展示的流式正文；候选提示尚未完整到达时暂不输出。
     *
     * @param content 当前已接收的完整文本
     * @return 可以发送给作者的正文
     */
    public static String visibleStreamingContent(String content) {
        if (content == null) {
            return null;
        }
        String result = content.stripLeading();
        boolean stripped = false;
        while (true) {
            String matchedNotice = matchingLeadingNotice(result);
            if (matchedNotice != null) {
                result = result.substring(matchedNotice.length()).stripLeading();
                stripped = true;
                continue;
            }
            if (isPartialLeadingNotice(result)) {
                return "";
            }
            if (!stripped) {
                return content;
            }
            if (result.endsWith(ASSISTANT_HISTORY_END)) {
                return result.substring(0, result.length() - ASSISTANT_HISTORY_END.length()).stripTrailing();
            }
            return result;
        }
    }

    private static String matchingLeadingNotice(String content) {
        for (String notice : LEADING_NOTICES) {
            if (content.startsWith(notice)) {
                return notice;
            }
        }
        return null;
    }

    private static boolean isPartialLeadingNotice(String content) {
        for (String notice : LEADING_NOTICES) {
            if (notice.startsWith(content)) {
                return true;
            }
        }
        return false;
    }

}
