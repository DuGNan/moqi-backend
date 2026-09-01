package com.dugnan.moqi.chapter.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(deep.controlSource()).isEqualTo("request");
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
    void prioritizesLatestNaturalLanguageDepthOverInheritedPreference() {
        assertThat(resolver.resolve(
                "请简洁确认：本章冲突发生在雨夜",
                null,
                Map.of("conversation", ReplyDepth.DEEP)).depth()).isEqualTo(ReplyDepth.BRIEF);
        assertThat(resolver.resolve(
                "只给结论，不要展开",
                null,
                Map.of("conversation", ReplyDepth.DEEP)).depth()).isEqualTo(ReplyDepth.BRIEF);
        assertThat(resolver.resolve(
                "请完整推演这项改动的长期影响",
                null,
                Map.of("conversation", ReplyDepth.BRIEF)).depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(resolver.resolve(
                "先深入分析背景，但最终只给结论",
                null,
                Map.of()).depth()).isEqualTo(ReplyDepth.BRIEF);
        assertThat(resolver.resolve(
                "先简短说明现状，再深入推演长期影响",
                null,
                Map.of()).depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(resolver.resolve(
                "控件虽然选了简洁，但请深入分析",
                new ReplyControlRequest("brief", "auto", null),
                Map.of()).depth()).isEqualTo(ReplyDepth.DEEP);
    }

    @Test
    void recognizesBalancedDepthAndHandlesNegationWithoutSubstringConflicts() {
        assertThat(resolver.resolve("请按平衡深度说明", null, Map.of()).depth())
                .isEqualTo(ReplyDepth.BALANCED);
        assertThat(resolver.resolve("适中展开即可", null, Map.of("conversation", ReplyDepth.DEEP)).depth())
                .isEqualTo(ReplyDepth.BALANCED);
        assertThat(resolver.resolve("不要深入", null, Map.of()).depth())
                .isEqualTo(ReplyDepth.BALANCED);
        assertThat(resolver.resolve("不要深入，简洁说", null, Map.of()).depth())
                .isEqualTo(ReplyDepth.BRIEF);
        assertThat(resolver.resolve("不要简略，详细分析", null, Map.of()).depth())
                .isEqualTo(ReplyDepth.DEEP);
    }

    @Test
    void distinguishesScopedMixedDepthFromAuthorRevision() {
        assertThat(resolver.resolve(
                "先简要总结背景，再深入分析第二个方向",
                null,
                Map.of()).depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(resolver.resolve(
                "先深入分析背景，但最终只给结论",
                null,
                Map.of()).depth()).isEqualTo(ReplyDepth.BRIEF);
    }

    @Test
    void doesNotTreatDepthWordingOrLocalFactChangesAsSummaryActions() {
        ResolvedReplyPolicy brief = resolver.resolve("简短说明人物为什么背叛", null, Map.of());
        ResolvedReplyPolicy complexCharacter = resolver.resolve("深入分析这个复杂人物的选择", null, Map.of());
        ResolvedReplyPolicy confirmation = resolver.resolve("只确认这一点：事故发生在雨夜", null, Map.of());
        ResolvedReplyPolicy withdrawal = resolver.resolve("只撤回这个设定：主角会读心", null, Map.of());

        assertThat(brief.mode()).isEqualTo(ReplyMode.EXPLORE);
        assertThat(brief.depth()).isEqualTo(ReplyDepth.BRIEF);
        assertThat(complexCharacter.mode()).isEqualTo(ReplyMode.EXPLORE);
        assertThat(complexCharacter.depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(confirmation.mode()).isEqualTo(ReplyMode.EXPLORE);
        assertThat(confirmation.depth()).isEqualTo(ReplyDepth.BRIEF);
        assertThat(confirmation.scope().targetReference()).isEqualTo("事故发生在雨夜");
        assertThat(withdrawal.mode()).isEqualTo(ReplyMode.EXPLORE);
        assertThat(withdrawal.depth()).isEqualTo(ReplyDepth.BRIEF);
        assertThat(withdrawal.scope().targetReference()).isEqualTo("只撤回这个设定：主角会读心");
        ResolvedReplyPolicy localCorrection = resolver.resolve(
                "只纠正一点：失踪的是妹妹，不是姐姐，不需要重建后续影响。",
                null,
                Map.of());
        assertThat(localCorrection.depth()).isEqualTo(ReplyDepth.BRIEF);
        assertThat(localCorrection.scope().targetType()).isEqualTo("current_focus");
        assertThat(localCorrection.scope().allowedChanges()).isEqualTo("fact_correction");
        assertThat(localCorrection.scope().targetReference()).isEqualTo("失踪的是妹妹，不是姐姐");

        ResolvedReplyPolicy rebuild = resolver.resolve(
                "只纠正一点：失踪的是妹妹，不是姐姐，并重建这项变化的影响",
                null,
                Map.of());
        assertThat(rebuild.scope().allowedChanges()).isEqualTo("changes_only");
        assertThat(rebuild.scope().targetReference()).isNull();
    }

    @ParameterizedTest
    @MethodSource("automaticDepthCases")
    void resolvesAutomaticDepthWithoutBindingDiscussionAction(String content, ReplyMode mode, ReplyDepth depth) {
        ResolvedReplyPolicy result = resolver.resolve(content, null, Map.of());

        assertThat(result.mode()).isEqualTo(mode);
        assertThat(result.depth()).isEqualTo(depth);
    }

    @Test
    void keepsCandidateBudgetIndependentFromDepth() {
        ResolvedReplyPolicy brief = resolver.resolve(
                "比较三个方案",
                new ReplyControlRequest("brief", "auto", null),
                Map.of());
        ResolvedReplyPolicy deep = resolver.resolve(
                "深入比较三个方案",
                new ReplyControlRequest("deep", "auto", null),
                Map.of());
        ResolvedReplyPolicy selectedDirection = resolver.resolve(
                "沿这个方向深入推演",
                null,
                Map.of());

        assertThat(brief.scope().maxCandidates()).isEqualTo(deep.scope().maxCandidates());
        assertThat(selectedDirection.depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(selectedDirection.scope().maxCandidates()).isEqualTo(1);
    }

    @Test
    void respectsExplicitCandidateCountWithoutUsingDepthAsCountSignal() {
        ResolvedReplyPolicy two = resolver.resolve("给我两个候选方向", null, Map.of());
        ResolvedReplyPolicy fiveDeep = resolver.resolve("深入比较五个方案", null, Map.of());
        ResolvedReplyPolicy oneWithModifier = resolver.resolve(
                "我没有思路。请先只给一个可继续讨论的方向，不要列多个方案。",
                null,
                Map.of());
        ResolvedReplyPolicy twoOpenings = resolver.resolve("简单比较这两个开篇", null, Map.of());
        ResolvedReplyPolicy noIdeas = resolver.resolve("我没有思路，给我几个可以继续讨论的切入点", null, Map.of());
        ResolvedReplyPolicy threeDimensions = resolver.resolve(
                "从人物自责、亲友关系和调查选择三个维度完整分析当前方向",
                null,
                Map.of());

        assertThat(two.mode()).isEqualTo(ReplyMode.EXPLORE);
        assertThat(two.scope().maxCandidates()).isEqualTo(2);
        assertThat(fiveDeep.mode()).isEqualTo(ReplyMode.COMPARE);
        assertThat(fiveDeep.depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(fiveDeep.scope().maxCandidates()).isEqualTo(1);
        assertThat(oneWithModifier.mode()).isEqualTo(ReplyMode.EXPLORE);
        assertThat(oneWithModifier.scope().maxCandidates()).isEqualTo(1);
        assertThat(twoOpenings.mode()).isEqualTo(ReplyMode.COMPARE);
        assertThat(twoOpenings.scope().maxCandidates()).isEqualTo(1);
        assertThat(noIdeas.mode()).isEqualTo(ReplyMode.EXPLORE);
        assertThat(noIdeas.scope().maxCandidates()).isEqualTo(3);
        assertThat(threeDimensions.mode()).isEqualTo(ReplyMode.EXPLORE);
        assertThat(threeDimensions.depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(threeDimensions.scope().maxCandidates()).isEqualTo(1);

        ResolvedReplyPolicy oneDirect = resolver.resolve("给我一个候选方向", null, Map.of());
        assertThat(oneDirect.mode()).isEqualTo(ReplyMode.EXPLORE);
        assertThat(oneDirect.scope().maxCandidates()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownControlValues() {
        assertThatThrownBy(() -> resolver.resolve(
                "继续讨论",
                new ReplyControlRequest("verbose", "auto", null),
                Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(
                "继续讨论",
                new ReplyControlRequest("auto", "chapter_everything", null),
                Map.of())).isInstanceOf(IllegalArgumentException.class);
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

        result = resolver.resolve(
                "只纠正一点：姐姐改成妹妹",
                null,
                Map.of("conversation", ReplyDepth.DEEP));
        assertThat(result.depth()).isEqualTo(ReplyDepth.BRIEF);
        assertThat(result.controlSource()).isEqualTo("system");
    }

    @Test
    void treatsClarificationAsBriefAndRestoresDeferredDepthOnlyForDirectAnswer() {
        ResolvedReplyPolicy clarification = resolver.resolve(
                "请深入调整一下这个",
                null,
                Map.of());

        assertThat(clarification.mode()).isEqualTo(ReplyMode.CLARIFY);
        assertThat(clarification.depth()).isEqualTo(ReplyDepth.BRIEF);
        assertThat(clarification.controlSource()).isEqualTo("clarification");
        assertThat(clarification.deferredDepth()).isEqualTo(ReplyDepth.DEEP);

        ReplyConversationSignals directAnswer = new ReplyConversationSignals(
                ReplyMode.CLARIFY, true, false, ReplyDepth.DEEP, true);
        assertThat(resolver.resolve("第二个方向", null, Map.of(), directAnswer).depth())
                .isEqualTo(ReplyDepth.DEEP);
        assertThat(resolver.resolve("总结前面的决定", null, Map.of(), directAnswer).depth())
                .isEqualTo(ReplyDepth.BALANCED);
        assertThat(resolver.resolve(
                "第二个方向，简洁说",
                null,
                Map.of(),
                directAnswer).depth()).isEqualTo(ReplyDepth.BRIEF);
    }

    @Test
    void keepsSoftConvergenceBehindExplicitActionsAndUsesFinalExplicitAction() {
        assertThat(resolver.resolve("方案太多，但请比较前两个", null, Map.of()).mode())
                .isEqualTo(ReplyMode.COMPARE);
        assertThat(resolver.resolve("先总结前文，然后写一段正文", null, Map.of()).mode())
                .isEqualTo(ReplyMode.DRAFT);
        assertThat(resolver.resolve("有点复杂了", null, Map.of()).mode())
                .isEqualTo(ReplyMode.CONVERGE);
    }

    @Test
    void defaultsToExploreAndSuppressesConsecutiveQuestionsAfterShortAnswer() {
        ResolvedReplyPolicy defaultPolicy = resolver.resolve("我想让觉醒更有代价", null, Map.of());
        assertThat(defaultPolicy.mode()).isEqualTo(ReplyMode.EXPLORE);
        assertThat(defaultPolicy.scope().allowedChanges()).isEqualTo("discussion_expansion");

        ResolvedReplyPolicy afterChoice = resolver.resolve(
                "B，再保留一点悬念",
                null,
                Map.of(),
                new ReplyConversationSignals(ReplyMode.COMPARE, true, true));
        assertThat(afterChoice.mode()).isEqualTo(ReplyMode.EXPLORE);
        assertThat(afterChoice.consecutiveQuestionSuppressed()).isTrue();
        assertThat(afterChoice.previousMode()).isEqualTo(ReplyMode.COMPARE);

        ResolvedReplyPolicy ambiguousAfterOptions = resolver.resolve(
                "这个调整一下。",
                null,
                Map.of(),
                new ReplyConversationSignals(ReplyMode.COMPARE, true, true));
        assertThat(ambiguousAfterOptions.mode()).isEqualTo(ReplyMode.CLARIFY);
        assertThat(ambiguousAfterOptions.consecutiveQuestionSuppressed()).isFalse();
    }

    @Test
    void onlyAllowsCrossChapterScopeWhenUserExplicitlyRequestsIt() {
        assertThat(resolver.resolve("继续完善这一章", null, Map.of()).crossChapterRequested()).isFalse();
        assertThat(resolver.resolve("顺便讨论下一章怎么衔接", null, Map.of()).crossChapterRequested()).isTrue();
    }

    private static Stream<Arguments> chapter62Cases() {
        return Stream.of(
                Arguments.of("现代都市超能文", ReplyMode.EXPLORE, ReplyDepth.BALANCED, "discussion_expansion"),
                Arguments.of("给我更多能力选择", ReplyMode.EXPLORE, ReplyDepth.BALANCED, "discussion_expansion"),
                Arguments.of("有点复杂了", ReplyMode.CONVERGE, ReplyDepth.BALANCED,
                        "confirmed_and_pending_summary"),
                Arguments.of("世界要架空", ReplyMode.CLARIFY, ReplyDepth.BRIEF, "question_only"),
                Arguments.of("只把国家名字架空", ReplyMode.EXPLORE, ReplyDepth.BALANCED, "changes_only"),
                Arguments.of("只调整第一章觉醒方式", ReplyMode.EXPLORE, ReplyDepth.BALANCED, "changes_only"),
                Arguments.of("请做完整章节设计", ReplyMode.PLAN, ReplyDepth.BALANCED, "requested_plan"),
                Arguments.of("写一段正文草稿", ReplyMode.DRAFT, ReplyDepth.BALANCED, "requested_draft"),
                Arguments.of("总结并确认我们已经决定的内容", ReplyMode.CONVERGE, ReplyDepth.BALANCED,
                        "confirmed_and_pending_summary"),
                Arguments.of("详细展开当前能力", ReplyMode.EXPLORE, ReplyDepth.DEEP, "discussion_expansion"),
                Arguments.of("比较三个方案", ReplyMode.COMPARE, ReplyDepth.BALANCED, "candidate_summaries"));
    }

    private static Stream<Arguments> automaticDepthCases() {
        return Stream.of(
                Arguments.of("只纠正一点：姐姐改成妹妹", ReplyMode.EXPLORE, ReplyDepth.BRIEF),
                Arguments.of("只纠正一点：姐姐改成妹妹，并重建这项变化的影响", ReplyMode.EXPLORE,
                        ReplyDepth.BALANCED),
                Arguments.of("我没有思路，帮我打开空间", ReplyMode.EXPLORE, ReplyDepth.BALANCED),
                Arguments.of("写一句开场正文", ReplyMode.DRAFT, ReplyDepth.BRIEF),
                Arguments.of("详细写完整场景", ReplyMode.DRAFT, ReplyDepth.DEEP),
                Arguments.of("简单比较这两个开篇的核心差异", ReplyMode.COMPARE, ReplyDepth.BALANCED),
                Arguments.of("从人物动机、线索节奏和读者预期多个维度完整比较这两个方案",
                        ReplyMode.COMPARE, ReplyDepth.DEEP),
                Arguments.of("这个调整一下", ReplyMode.CLARIFY, ReplyDepth.BRIEF),
                Arguments.of("总结我们已经确认的内容", ReplyMode.CONVERGE, ReplyDepth.BALANCED),
                Arguments.of("深入总结我们已经确认的内容", ReplyMode.CONVERGE, ReplyDepth.DEEP));
    }
}
