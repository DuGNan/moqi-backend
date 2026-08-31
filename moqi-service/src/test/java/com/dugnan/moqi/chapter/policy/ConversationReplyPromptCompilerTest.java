package com.dugnan.moqi.chapter.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * @author dgn
 * @date 2026-08-26
 * @description 验证章节讨论策略只编译为模型可理解的自然中文。
 */
class ConversationReplyPromptCompilerTest {

    private static final List<String> FORBIDDEN_TERMS = List.of(
            "primaryIntent",
            "allowedChanges",
            "maxCandidates",
            "权威状态",
            "confirmed",
            "candidate",
            "pending",
            "rejected",
            "evidence",
            "约 150 至 400 字",
            "最多出现一个问号",
            "schemaVersion",
            "single_choice",
            "open_question",
            "questionId",
            "optionId",
            "allowCustom",
            "content",
            "interaction");

    private final ConversationReplyPromptCompiler compiler = new ConversationReplyPromptCompiler();

    @Test
    void compilesNaturalChineseWithoutInternalPolicyTerms() {
        for (ReplyMode mode : ReplyMode.values()) {
            for (ReplyDepth depth : ReplyDepth.values()) {
                String prompt = compiler.compile(input(mode, depth, scope(mode)));

                assertThat(prompt).doesNotContain(FORBIDDEN_TERMS.toArray(String[]::new));
                assertThat(prompt).contains("作者").contains("本轮");
            }
        }
    }

    @Test
    void keepsActionScopeAndDepthConsistent() {
        String compareDeep = compiler.compile(input(
                ReplyMode.COMPARE,
                ReplyDepth.DEEP,
                new ReplyScope("compare_candidates", "current_discussion", null,
                        "candidate_summaries", 1, false)));
        String exploreDeep = compiler.compile(input(
                ReplyMode.EXPLORE,
                ReplyDepth.DEEP,
                new ReplyScope("explore_direction", "current_focus", "主角的选择",
                        "changes_only", 1, false)));
        String exploreCandidates = compiler.compile(input(
                ReplyMode.EXPLORE,
                ReplyDepth.BALANCED,
                new ReplyScope("explore_direction", "current_discussion", null,
                        "discussion_expansion", 3, false)));

        assertThat(compareDeep)
                .contains("比较")
                .contains("本轮深入比较")
                .contains("对象数量按实际识别结果")
                .contains("标题", "说明", "取舍")
                .doesNotContain("schemaVersion", "single_choice", "questionId", "optionId", "allowCustom");
        assertThat(exploreDeep)
                .contains("沿作者当前想法继续讨论")
                .contains("主角的选择")
                .contains("本轮深入推演")
                .contains("不要自行补造作者未给出的机制、事件、证据、阶段或结局")
                .contains("触发条件、选择方式、先后顺序、强弱程度和可控性都保持未知")
                .contains("不用具体人物、事件或经历替作者补充例子")
                .contains("不推定其他人物知道什么或采取什么行动")
                .contains("不能借此补入新前提或新过程")
                .contains("完成当前推演后直接结束")
                .contains("只分析已有代价形成的选择压力")
                .contains("不替作者给出具体选择或结局")
                .contains("不追加下一步建议、可继续讨论的角度、可选方向或不同结局")
                .doesNotContain("本轮提供两个", "本轮提供三个", "本轮提供四个", "本轮提供五个")
                .doesNotContain("因果压力", "因果落点", "一条连贯因果链", "尚未确认的候选");
        assertThat(exploreCandidates)
                .contains("本轮提供三个实质不同的方向")
                .doesNotContain("作者没有要求多个方向时")
                .doesNotContain("作者指定的焦点")
                .doesNotContain("JSON", "single_choice", "interaction", "立即选择");
    }

    @Test
    void protectsCorrectionClarificationComparisonAndSummaryBoundaries() {
        String correction = compiler.compile(input(
                ReplyMode.EXPLORE,
                ReplyDepth.BRIEF,
                new ReplyScope("explore_direction", "current_focus", null,
                        "fact_correction", 1, false)));
        String clarification = compiler.compile(input(
                ReplyMode.CLARIFY, ReplyDepth.BRIEF, scope(ReplyMode.CLARIFY)));
        String comparison = compiler.compile(input(
                ReplyMode.COMPARE,
                ReplyDepth.DEEP,
                new ReplyScope("compare_candidates", "current_discussion", null,
                        "candidate_summaries", 1, false)));
        String summary = compiler.compile(input(
                ReplyMode.CONVERGE, ReplyDepth.DEEP, scope(ReplyMode.CONVERGE)));

        assertThat(correction)
                .contains("只回复作者消息中更正后的事实")
                .contains("不追加确认语或其他说明")
                .doesNotContain("作者刚刚更正为")
                .doesNotContain("当前作品资料", "作者最新消息")
                .doesNotContain("沿作者当前想法继续讨论", "平衡回答", "深入推演", "助手此前的建议");
        assertThat(clarification)
                .contains("只澄清一个会明显改变回答的关键歧义")
                .contains("得到答案后下一轮再讨论怎么改")
                .contains("‘回复’结合对话简短说明无法确定的具体内容")
                .contains("需要作者回答的内容只写在‘问题’中")
                .contains("只确认这一歧义具体指向什么")
                .contains("只使用对话中已经出现的对象")
                .contains("不同时询问如何修改")
                .doesNotContain("必须逐字", "具体指的是哪一项？", "{\"回复\"")
                .doesNotContain("主要依据和直接影响", "schemaVersion", "open_question", "questionId", "allowCustom");
        assertThat(comparison)
                .contains("本轮只比较作者提出或要求的方向")
                .contains("分别说明各自的效果和代价，把选择留给作者")
                .contains("从作者最新消息和已有对话识别实际比较对象")
                .contains("永久、不可逆、否定和未知边界必须保持不变")
                .contains("不能为了比较引入恢复、例外或反转")
                .contains("不补造血统、市场、组织或能力效果")
                .contains("资料不足时明确说明取决于后续设定")
                .contains("不要用可能形成的血统、市场、组织、偏见、阶层规则或额外能力机制填满维度")
                .contains("不能可靠识别时")
                .contains("标题", "说明", "取舍")
                .doesNotContain("{\"回复\"")
                .doesNotContain("content", "interaction", "options", "schemaVersion", "single_choice")
                .doesNotContain("二至两个");
        assertThat(summary)
                .contains("准确地复述作者要求总结的内容")
                .contains("以作者消息为准")
                .contains("保持原有的确定、待定或否定状态")
                .contains("保留当前上下文中每一项仍然有效的决定、候选、纠正、否定和待决内容")
                .contains("回复深度只控制压缩和说明程度，不得用来删减事项")
                .contains("多项内容用分行或分段保持清楚，不把项目首尾粘连在一起")
                .contains("先准确复述各项决定")
                .contains("共同产生的直接效果")
                .contains("事实名词只复用作者原话")
                .contains("只把作者已经说出的事项连起来")
                .contains("不补充作者未说出的背景、原因、证据或过程")
                .contains("不要把‘未知’改写成无法验证或缺少证据")
                .contains("不要引入叙述者、情节安排或验证方式")
                .contains("不解释缺少什么，也不推定人物如何理解")
                .contains("开头直接进入内容")
                .contains("本轮深入总结")
                .doesNotContain("原话依据", "明确采纳作为依据", "未新增事实", "边界说明")
                .doesNotContain("本轮深入推演")
                .doesNotContain("三个简短段落", "暂无作者明确", "占位语输出");
    }

    @Test
    void preservesCompleteCurrentPromptSnapshots() {
        String exploreBalanced = compiler.compile(input(
                ReplyMode.EXPLORE,
                ReplyDepth.BALANCED,
                new ReplyScope("explore_direction", "current_focus", "主角是否坦白",
                        "changes_only", 1, false)));
        String convergeDeep = compiler.compile(input(
                ReplyMode.CONVERGE,
                ReplyDepth.DEEP,
                new ReplyScope("converge_consensus", "current_discussion", null,
                        "confirmed_and_pending_summary", 1, false)));

        assertThat(exploreBalanced).isEqualTo(
                "使用自然、直接、容易理解的中文，回应作者当前提出的问题。"
                        + "本轮沿作者当前想法继续讨论；缺失信息会明显改变回答时再提问。"
                        + "本轮平衡回答，说明主要依据和直接影响，信息充分后收束。"
                        + "只沿当前回答方向展开，不在结尾追加多个可选分支。"
                        + "当前只处理本章中作者指定的焦点：主角是否坦白。"
                        + "结合当前作品资料理解问题，以作者最新消息为准。"
                        + "复述作者内容时，保留原有的否定和不确定措辞。");
        assertThat(convergeDeep).isEqualTo(
                "使用自然、直接、容易理解的中文，回应作者当前提出的问题。"
                        + "本轮直接、准确地复述作者要求总结的内容，以作者消息为准，保持原有的确定、待定或否定状态。"
                        + "作者没有明确缩小总结范围时，保留当前上下文中每一项仍然有效的决定、候选、纠正、否定和待决内容；"
                        + "回复深度只控制压缩和说明程度，不得用来删减事项。"
                        + "多项内容用分行或分段保持清楚，不把项目首尾粘连在一起。"
                        + "开头直接进入内容，不说明整理过程或是否新增事实。"
                        + "本轮深入总结，先准确复述各项决定，再说明它们共同产生的直接效果。"
                        + "事实名词只复用作者原话；关系说明只把作者已经说出的事项连起来，"
                        + "不补充作者未说出的背景、原因、证据或过程。"
                        + "不要把‘未知’改写成无法验证或缺少证据，也不要引入叙述者、情节安排或验证方式。"
                        + "共同效果只说明这些确定、未知和否定事项并置后，作者要求保留的状态如何继续成立；"
                        + "不解释缺少什么，也不推定人物如何理解。"
                        + "当前范围是本章正在讨论的问题。"
                        + "结合当前作品资料理解问题，以作者最新消息为准。"
                        + "复述作者内容时，保留原有的否定和不确定措辞。");
    }

    @Test
    void supportsKnownLegacySnapshotMappings() {
        ConversationReplyTaskInputV1 legacy = input(
                ReplyMode.DRAFT,
                ReplyDepth.DEEP,
                new ReplyScope("draft_prose", "chapter_draft", null,
                        "requested_draft", 1, true),
                "chapter-reply-policy-v3");

        assertThat(compiler.compile(legacy)).contains("正文草稿").contains("深入回复");
    }

    @Test
    void preservesVersionThreePromptForLegacyTasksWithoutChangingTheirSavedPolicy() {
        ConversationReplyTaskInputV1 legacy = input(
                ReplyMode.EXPLORE, ReplyDepth.BALANCED, scope(ReplyMode.EXPLORE),
                "chapter-reply-policy-v3");

        assertThat(compiler.compile(legacy))
                .contains("因果压力")
                .contains("一条连贯因果链");
    }

    @Test
    void preservesVersionFourPromptForTasksCreatedBeforeTheNewContract() {
        ConversationReplyTaskInputV1 versionFour = input(
                ReplyMode.CLARIFY, ReplyDepth.BALANCED, scope(ReplyMode.CLARIFY),
                "chapter-reply-policy-v4");

        assertThat(compiler.compile(versionFour))
                .contains("你是墨契的章节共创搭档")
                .contains("schemaVersion=1", "type=open_question", "questionId", "allowCustom=true");
    }

    @Test
    void rejectsMissingOrUnknownMappings() {
        assertThatThrownBy(() -> compiler.compile(input(
                ReplyMode.EXPLORE,
                ReplyDepth.BALANCED,
                new ReplyScope("new_internal_intent", "current_discussion", null,
                        "discussion_expansion", 1, false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primaryIntent");
        assertThatThrownBy(() -> compiler.compile(input(
                ReplyMode.EXPLORE,
                ReplyDepth.BALANCED,
                new ReplyScope("explore_direction", null, null,
                        "discussion_expansion", 1, false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("targetType");
        assertThatThrownBy(() -> compiler.compile(new ConversationReplyTaskInputV1(
                ConversationReplyTaskInputV1.SCHEMA_VERSION,
                11L,
                8L,
                ReplyMode.EXPLORE,
                null,
                scope(ReplyMode.EXPLORE),
                "system",
                DefaultReplyPolicyResolver.POLICY_VERSION,
                ConversationReplyTaskInputV1.AUTHORITY_VERSION,
                false,
                null,
                null,
                false,
                false,
                null))).isInstanceOf(IllegalStateException.class).hasMessageContaining("replyDepth");
        assertThatThrownBy(() -> compiler.compile(input(
                ReplyMode.COMPARE,
                ReplyDepth.BALANCED,
                new ReplyScope("compare_candidates", "current_discussion", null,
                        "candidate_summaries", 1, false),
                "chapter-reply-policy-v4")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少需要两个候选");
        assertThatThrownBy(() -> compiler.compile(input(
                ReplyMode.EXPLORE, ReplyDepth.BALANCED, scope(ReplyMode.EXPLORE),
                "chapter-reply-policy-v99")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("policyVersion");
    }

    private ConversationReplyTaskInputV1 input(ReplyMode mode, ReplyDepth depth, ReplyScope scope) {
        return input(mode, depth, scope, DefaultReplyPolicyResolver.POLICY_VERSION);
    }

    private ConversationReplyTaskInputV1 input(
            ReplyMode mode,
            ReplyDepth depth,
            ReplyScope scope,
            String policyVersion) {
        return new ConversationReplyTaskInputV1(
                ConversationReplyTaskInputV1.SCHEMA_VERSION,
                11L,
                8L,
                mode,
                depth,
                scope,
                "system",
                policyVersion,
                ConversationReplyTaskInputV1.AUTHORITY_VERSION,
                false,
                null,
                null,
                false,
                false,
                null);
    }

    private ReplyScope scope(ReplyMode mode) {
        return switch (mode) {
            case EXPLORE -> new ReplyScope(
                    "explore_direction", "current_discussion", null, "discussion_expansion", 1, false);
            case CLARIFY -> new ReplyScope(
                    "clarify_direction", "current_discussion", null, "question_only", 1, false);
            case COMPARE -> new ReplyScope(
                    "compare_candidates", "current_discussion", null, "candidate_summaries", 1, false);
            case CONVERGE -> new ReplyScope(
                    "converge_consensus", "current_discussion", null,
                    "confirmed_and_pending_summary", 1, false);
            case PLAN -> new ReplyScope(
                    "build_plan", "chapter_plan", null, "requested_plan", 1, true);
            case DRAFT -> new ReplyScope(
                    "write_draft", "chapter_prose", null, "requested_draft", 1, true);
        };
    }
}
