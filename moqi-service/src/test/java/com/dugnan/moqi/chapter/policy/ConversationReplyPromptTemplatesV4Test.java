package com.dugnan.moqi.chapter.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 验证章节讨论策略 V4 模板保持动作、深度和候选数量相互独立。
 */
class ConversationReplyPromptTemplatesV4Test {

    @Test
    void separatesIdentityActionDepthScopeAuthorityAndInteractionTemplates() {
        ReplyScope scope = new ReplyScope(
                "explore_direction", "current_focus", "人物是否坦白", "fact_correction", 1, false);

        assertThat(ConversationReplyPromptTemplatesV4.identity())
                .isEqualTo("你是墨契的章节共创搭档。使用自然、直接、容易理解的中文，回应作者当前提出的问题。");
        assertThat(ConversationReplyPromptTemplatesV4.action(ReplyMode.EXPLORE))
                .contains("沿作者当前想法继续讨论")
                .doesNotContain("简洁回答", "平衡回答", "深入回答");
        assertThat(ConversationReplyPromptTemplatesV4.depth(ReplyMode.EXPLORE, ReplyDepth.DEEP))
                .contains("深入推演当前问题", "沿一条连续变化", "不列替代方向")
                .contains("‘可以’或‘可能’的待讨论内容")
                .contains("不当作作者已经确定的事实")
                .doesNotContain("多个方向", "候选数量");
        assertThat(ConversationReplyPromptTemplatesV4.action(ReplyMode.COMPARE))
                .contains("分别说明各自的效果和代价，把选择留给作者");
        assertThat(ConversationReplyPromptTemplatesV4.scope(scope))
                .contains("作者这次明确表达的是：‘人物是否坦白’", "本轮只复述这项变更");
        assertThat(ConversationReplyPromptTemplatesV4.authority())
                .isEqualTo("以当前作品资料和作者已经明确确定的内容为依据；作者最新消息优先。");
        assertThat(ConversationReplyPromptTemplatesV4.interaction(ReplyMode.EXPLORE, 1)).isEmpty();
    }

    @Test
    void keepsDepthTemplatesFocusedOnInformationCoverageInsteadOfOutputFormat() {
        assertThat(ConversationReplyPromptTemplatesV4.depth(ReplyMode.EXPLORE, ReplyDepth.BRIEF))
                .contains("理解当前答案所需的信息")
                .doesNotContain("段落", "字", "候选");
        assertThat(ConversationReplyPromptTemplatesV4.depth(ReplyMode.EXPLORE, ReplyDepth.BALANCED))
                .contains("主要依据和直接影响")
                .doesNotContain("段落", "字", "候选");
        assertThat(ConversationReplyPromptTemplatesV4.depth(ReplyMode.EXPLORE, ReplyDepth.DEEP))
                .contains("前提之间的因果、取舍和影响")
                .doesNotContain("段落", "字", "候选数量");
        assertThat(ConversationReplyPromptTemplatesV4.depth(ReplyMode.CONVERGE, ReplyDepth.DEEP))
                .contains("直接关系和边界")
                .doesNotContain("推演", "新设定");
    }

    @Test
    void emitsCandidateCountOnlyWhenTheCurrentActionNeedsIt() {
        assertThat(ConversationReplyPromptTemplatesV4.candidateCount(ReplyMode.EXPLORE, 1)).isEmpty();
        assertThat(ConversationReplyPromptTemplatesV4.candidateCount(ReplyMode.EXPLORE, 3))
                .isEqualTo("本轮提供三个实质不同的方向。");
        assertThat(ConversationReplyPromptTemplatesV4.interaction(ReplyMode.EXPLORE, 3)).isEmpty();
        assertThat(ConversationReplyPromptTemplatesV4.candidateCount(ReplyMode.COMPARE, 2))
                .isEqualTo("本轮比较两个方向，不增加其他方向。");
        assertThat(ConversationReplyPromptTemplatesV4.candidateCount(ReplyMode.CONVERGE, 1)).isEmpty();
        assertThat(ConversationReplyPromptTemplatesV4.candidateCount(ReplyMode.CLARIFY, 1)).isEmpty();
    }

    @Test
    void keepsConvergenceLimitedToAuthorSupportedContent() {
        String convergence = ConversationReplyPromptTemplatesV4.action(ReplyMode.CONVERGE)
                + ConversationReplyPromptTemplatesV4.depth(ReplyMode.CONVERGE, ReplyDepth.DEEP);

        assertThat(convergence)
                .contains("作者原话或明确采纳作为依据")
                .contains("作者已经确认的信息及其直接关系和边界")
                .contains("不增加事实")
                .doesNotContain("仍待决定", "已经否定", "暂无", "下一步");
    }
}
