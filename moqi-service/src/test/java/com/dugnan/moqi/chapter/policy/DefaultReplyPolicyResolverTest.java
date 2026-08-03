package com.dugnan.moqi.chapter.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ReplyControlRequest;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 固定回归章节 62 暴露的过度扩写、歧义和版本收敛场景。
 */
class DefaultReplyPolicyResolverTest {

    private final DefaultReplyPolicyResolver resolver = new DefaultReplyPolicyResolver();

    @ParameterizedTest
    @MethodSource("chapter62Cases")
    void resolvesChapter62Cases(
            String content,
            ReplyMode expectedMode,
            ReplyDepth expectedDepth,
            String allowedChanges) {
        ResolvedReplyPolicy result = resolver.resolve(content, null, Map.of());

        assertThat(result.mode()).isEqualTo(expectedMode);
        assertThat(result.depth()).isEqualTo(expectedDepth);
        assertThat(result.scope().allowedChanges()).isEqualTo(allowedChanges);
        assertThat(result.scope().maxCandidates()).isLessThanOrEqualTo(3);
    }

    @Test
    void supportsAllThreeDepthsAndMessageOverride() {
        assertThat(resolver.resolve(
                "给我几个方案",
                new ReplyControlRequest("brief", "auto", null),
                Map.of()).depth()).isEqualTo(ReplyDepth.BRIEF);
        assertThat(resolver.resolve(
                "给我几个方案",
                new ReplyControlRequest("balanced", "auto", null),
                Map.of()).depth()).isEqualTo(ReplyDepth.BALANCED);
        ResolvedReplyPolicy deep = resolver.resolve(
                "继续展开当前能力",
                new ReplyControlRequest("deep", "current_only", "主角能力"),
                Map.of("conversation", ReplyDepth.BRIEF));
        assertThat(deep.depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(deep.controlSource()).isEqualTo("message");
        assertThat(deep.scope().allowedChanges()).isEqualTo("changes_only");
        assertThat(deep.scope().targetReference()).isEqualTo("主角能力");

        ResolvedReplyPolicy naturalLanguage = resolver.resolve(
                "详细展开当前能力",
                null,
                Map.of("conversation", ReplyDepth.BRIEF));
        assertThat(naturalLanguage.depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(naturalLanguage.controlSource()).isEqualTo("message");
    }

    @Test
    void appliesPreferencePriorityFromConversationToUser() {
        Map<String, ReplyDepth> preferences = Map.of(
                "conversation", ReplyDepth.DEEP,
                "chapter", ReplyDepth.BALANCED,
                "work", ReplyDepth.BRIEF,
                "user", ReplyDepth.BRIEF);
        ResolvedReplyPolicy result = resolver.resolve("给我几个方案", null, preferences);
        assertThat(result.depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(result.controlSource()).isEqualTo("conversation");

        result = resolver.resolve(
                "给我几个方案",
                null,
                Map.of("chapter", ReplyDepth.BALANCED, "work", ReplyDepth.DEEP, "user", ReplyDepth.BRIEF));
        assertThat(result.depth()).isEqualTo(ReplyDepth.BALANCED);
        assertThat(result.controlSource()).isEqualTo("chapter");

        result = resolver.resolve(
                "给我几个方案",
                null,
                Map.of("work", ReplyDepth.DEEP, "user", ReplyDepth.BRIEF));
        assertThat(result.depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(result.controlSource()).isEqualTo("work");

        result = resolver.resolve("给我几个方案", null, Map.of("user", ReplyDepth.BALANCED));
        assertThat(result.depth()).isEqualTo(ReplyDepth.BALANCED);
        assertThat(result.controlSource()).isEqualTo("user");
    }

    private static Stream<Arguments> chapter62Cases() {
        return Stream.of(
                Arguments.of("现代都市超能文", ReplyMode.CLARIFY, ReplyDepth.BRIEF, "question_only"),
                Arguments.of("给我更多能力选择", ReplyMode.COMPARE, ReplyDepth.BRIEF, "candidate_summaries"),
                Arguments.of("有点复杂了", ReplyMode.CONVERGE, ReplyDepth.BRIEF, "confirmed_and_pending_summary"),
                Arguments.of("世界要架空", ReplyMode.CLARIFY, ReplyDepth.BRIEF, "question_only"),
                Arguments.of("只把国家名字架空", ReplyMode.CLARIFY, ReplyDepth.BRIEF, "question_only"),
                Arguments.of("只调整第一章觉醒方式", ReplyMode.CLARIFY, ReplyDepth.BRIEF, "changes_only"),
                Arguments.of("请做完整章节设计", ReplyMode.PLAN, ReplyDepth.BALANCED, "requested_plan"),
                Arguments.of("写一段正文草稿", ReplyMode.DRAFT, ReplyDepth.DEEP, "requested_draft"),
                Arguments.of("总结并确认我们已经决定的内容", ReplyMode.CONVERGE, ReplyDepth.BRIEF,
                        "confirmed_and_pending_summary"),
                Arguments.of("详细展开当前能力", ReplyMode.CLARIFY, ReplyDepth.DEEP, "question_only"),
                Arguments.of("比较三个方案", ReplyMode.COMPARE, ReplyDepth.BRIEF, "candidate_summaries"));
    }
}
