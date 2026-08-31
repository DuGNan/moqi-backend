package com.dugnan.moqi.chapter.policy;

import java.util.Set;

import org.springframework.util.StringUtils;

/**
 * @author dgn
 * @date 2026-08-26
 * @description 将章节讨论策略快照编译为模型可直接执行的自然中文规则。
 */
public final class ConversationReplyPromptCompiler {

    private static final Set<String> LEGACY_POLICY_VERSIONS = Set.of(
            "chapter-reply-policy-v1", "chapter-reply-policy-v2", "chapter-reply-policy-v3");
    private static final String POLICY_VERSION_FOUR = "chapter-reply-policy-v4";

    private static final int MIN_CANDIDATE_COUNT = 1;
    private static final int MAX_CANDIDATE_COUNT = 5;
    private static final int MIN_COMPARISON_CANDIDATE_COUNT = 2;
    private static final String TARGET_CURRENT_FOCUS = "current_focus";
    private static final String TARGET_CHAPTER_PLAN = "chapter_plan";
    private static final String TARGET_CHAPTER_PROSE = "chapter_prose";
    private static final String TARGET_CHAPTER_DRAFT = "chapter_draft";
    private static final String CHANGES_ONLY = "changes_only";
    private static final String FACT_CORRECTION = "fact_correction";
    private static final Set<String> PRIMARY_INTENTS = Set.of(
            "explore_direction", "clarify_direction", "compare_candidates", "converge_consensus",
            "build_plan", "write_draft", "draft_prose", "discuss");
    private static final Set<String> TARGET_TYPES = Set.of(
            "current_discussion", "current_focus", "chapter_plan", "chapter_prose", "chapter_draft");
    private static final Set<String> ALLOWED_CHANGES = Set.of(
            "discussion_expansion", "question_only", "candidate_summaries", "confirmed_and_pending_summary",
            "requested_plan", "requested_draft", CHANGES_ONLY, FACT_CORRECTION, "discussion");

    /**
     * 编译不可变策略快照，任何缺失或未知映射都会阻止请求。
     *
     * @param input 任务创建时保存的最终策略
     * @return 模型可见的自然中文规则
     */
    public String compile(ConversationReplyTaskInputV1 input) {
        validate(input);
        if (LEGACY_POLICY_VERSIONS.contains(input.policyVersion())) {
            return compileLegacy(input);
        }
        if (POLICY_VERSION_FOUR.equals(input.policyVersion())) {
            return compileVersionFour(input);
        }
        return compileVersionFive(input);
    }

    private String compileVersionFive(ConversationReplyTaskInputV1 input) {
        if (FACT_CORRECTION.equals(input.replyScope().allowedChanges())) {
            return ConversationReplyPromptTemplatesV5.style()
                    + ConversationReplyPromptTemplatesV5.scope(input.replyScope());
        }
        return ConversationReplyPromptTemplatesV5.style()
                + ConversationReplyPromptTemplatesV5.action(input.replyMode())
                + ConversationReplyPromptTemplatesV5.depth(input.replyMode(), input.replyDepth())
                + ConversationReplyPromptTemplatesV5.candidateCount(
                        input.replyMode(), input.replyScope().maxCandidates())
                + ConversationReplyPromptTemplatesV5.scope(input.replyScope())
                + ConversationReplyPromptTemplatesV5.authority()
                + ConversationReplyPromptTemplatesV5.conversation(
                        input.consecutiveQuestionSuppressed(), input.crossChapterRequested())
                + ConversationReplyPromptTemplatesV5.interaction(input.replyMode());
    }

    private String compileVersionFour(ConversationReplyTaskInputV1 input) {
        if (FACT_CORRECTION.equals(input.replyScope().allowedChanges())) {
            return ConversationReplyPromptTemplatesV4.identity()
                    + ConversationReplyPromptTemplatesV4.scope(input.replyScope())
                    + ConversationReplyPromptTemplatesV4.authority()
                    + ConversationReplyPromptTemplatesV4.conversation(
                            input.consecutiveQuestionSuppressed(), input.crossChapterRequested());
        }
        return ConversationReplyPromptTemplatesV4.identity()
                + ConversationReplyPromptTemplatesV4.action(input.replyMode())
                + ConversationReplyPromptTemplatesV4.depth(input.replyMode(), input.replyDepth())
                + ConversationReplyPromptTemplatesV4.candidateCount(
                        input.replyMode(), input.replyScope().maxCandidates())
                + ConversationReplyPromptTemplatesV4.scope(input.replyScope())
                + ConversationReplyPromptTemplatesV4.authority()
                + ConversationReplyPromptTemplatesV4.conversation(
                        input.consecutiveQuestionSuppressed(), input.crossChapterRequested())
                + ConversationReplyPromptTemplatesV4.interaction(
                        input.replyMode(), input.replyScope().maxCandidates());
    }

    private String compileLegacy(ConversationReplyTaskInputV1 input) {
        return "你是墨契的章节共创助手。"
                + actionRule(input.replyMode())
                + depthRule(input.replyDepth())
                + scopeRule(input.replyScope())
                + authorityRule()
                + conversationRule(input)
                + "不得宣称已经确认、保存或更新任何 Brief、大纲、场景规划、正文或作品资料。"
                + structuredInteractionRule(input);
    }

    private void validate(ConversationReplyTaskInputV1 input) {
        if (input == null) {
            throw new IllegalStateException("conversation_reply 缺少策略快照");
        }
        if (input.replyMode() == null) {
            throw new IllegalStateException("conversation_reply 缺少 replyMode 自然语言映射");
        }
        if (input.replyDepth() == null) {
            throw new IllegalStateException("conversation_reply 缺少 replyDepth 自然语言映射");
        }
        if (!isSupportedPolicyVersion(input.policyVersion())) {
            throw new IllegalStateException("conversation_reply 缺少 policyVersion 自然语言映射");
        }
        ReplyScope scope = input.replyScope();
        if (scope == null) {
            throw new IllegalStateException("conversation_reply 缺少 replyScope 自然语言映射");
        }
        requireMapping(scope.primaryIntent(), PRIMARY_INTENTS, "primaryIntent");
        requireMapping(scope.targetType(), TARGET_TYPES, "targetType");
        requireMapping(scope.allowedChanges(), ALLOWED_CHANGES, "allowedChanges");
        if (scope.maxCandidates() < MIN_CANDIDATE_COUNT
                || scope.maxCandidates() > MAX_CANDIDATE_COUNT) {
            throw new IllegalStateException("conversation_reply 的 maxCandidates 缺少自然语言映射");
        }
        if (input.replyMode() == ReplyMode.COMPARE
                && scope.maxCandidates() < MIN_COMPARISON_CANDIDATE_COUNT
                && !DefaultReplyPolicyResolver.POLICY_VERSION.equals(input.policyVersion())) {
            throw new IllegalStateException("conversation_reply 的比较动作至少需要两个候选");
        }
    }

    private void requireMapping(String value, Set<String> mappings, String field) {
        if (!StringUtils.hasText(value) || !mappings.contains(value)) {
            throw new IllegalStateException("conversation_reply 缺少 " + field + " 自然语言映射");
        }
    }

    private boolean isSupportedPolicyVersion(String policyVersion) {
        return StringUtils.hasText(policyVersion)
                && (DefaultReplyPolicyResolver.POLICY_VERSION.equals(policyVersion)
                || POLICY_VERSION_FOUR.equals(policyVersion)
                || LEGACY_POLICY_VERSIONS.contains(policyVersion));
    }

    private String actionRule(ReplyMode mode) {
        return switch (mode) {
            case EXPLORE -> "本轮需要沿作者当前选择的方向继续思考；没有请求候选时，只推进一个最有价值的方向，"
                    + "整轮连续推演这一方向，不列出多个替代路径、结局或 A/B/C 式选择。"
                    + "回复只能形成一条连贯因果链，不用‘几种可能、方向、方案或选择’组织内容，也不在结尾追加其他候选。"
                    + "遇到尚未确定的结果时，先分析会影响作者判断的条件和因果压力；确实需要落到结果时，"
                    + "只给一个最有可能的因果落点并明确标为候选，禁止列出多个结果、二选一或折中方案。"
                    + "这个落点只能使用作者已经给出的设定、人物和关系，不得为了圆满而新增能力、机制、人物或事件。"
                    + "确实需要作者补充时再提出一个低负担的开放问题，避免连续追问。";
            case CLARIFY -> "本轮只澄清会实质改变回复的关键歧义；上下文足以判断时直接继续，确需澄清时只增加一个必要问题。"
                    + "指代不明确时，说明当前无法确定所指对象，不得猜测作者已经改选或否定任何候选，"
                    + "唯一的问题只询问这个指代具体指向什么。";
            case COMPARE -> "本轮需要比较作者提出或索要的候选。作者指定候选数量时严格遵守；未指定时只给少量实质不同、"
                    + "可以直接比较的方向，不用同义改写凑数，并保留作者自行描述的空间。"
                    + "作者只要求比较既有方案时，回复正文和选项都只能讨论这些方案，禁止提出合并、混合、第三方案或其他替代方案。";
            case CONVERGE -> "本轮只整理已经讨论的内容，清楚区分作者已确定的事实、尚待决定的问题和已经否定的方向，"
                    + "不借总结补造新设定、示例、假设、下一步建议或新问题，也不把因果解释写成作者已经确定的新事实；"
                    + "尚待决定的内容只按已有表达复述，不擅自展开其身份、数量、角度或其他子项；总结完成后直接收束。";
            case PLAN -> "本轮只在作者请求的范围内整理结构化规划，不生成正文，也不把规划候选当成正式内容。";
            case DRAFT -> "本轮只在作者请求的范围内生成正文草稿；草稿始终是待作者审阅的候选。";
        };
    }

    private String depthRule(ReplyDepth depth) {
        return switch (depth) {
            case BRIEF -> "本轮采用简洁回复：只保留当前决策需要的结论和最关键理由，不补充无关分支。"
                    + "作者只纠正局部事实且没有要求重建影响时，只复述修正后的事实，不推导连带变化。";
            case BALANCED -> "本轮采用平衡回复：提供足够理解和继续判断的信息，说明主要理由、关键影响或风险；"
                    + "问题说清后及时收束。";
            case DEEP -> "本轮采用深入回复：只围绕作者当前问题，完整推演关键因果、取舍、风险、反例和长期影响；"
                    + "不要借深入扩大讨论范围或补造设定，也不要因为深入而增加候选方向。";
        };
    }

    private String candidateCountRule(ConversationReplyTaskInputV1 input) {
        int count = input.replyScope().maxCandidates();
        if (input.replyMode() == ReplyMode.COMPARE) {
            return "作者要求" + chineseCandidateCount(count) + "方向，只提供这些方向并逐一比较。";
        }
        if (count == 1) {
            return "作者没有要求多个方向时，只给一个具体建议，不追加替代方案。";
        }
        return "候选数量以作者本轮要求为准，最多提供" + chineseCandidateCount(count) + "方向。";
    }

    private String scopeRule(ReplyScope scope) {
        StringBuilder rule = new StringBuilder();
        if (TARGET_CURRENT_FOCUS.equals(scope.targetType()) || CHANGES_ONLY.equals(scope.allowedChanges())) {
            rule.append("本轮只处理作者指定的当前焦点，不重做完整世界观、整章设计或其他已确定内容。");
        } else if (TARGET_CHAPTER_PLAN.equals(scope.targetType())) {
            rule.append("当前范围是本章规划，不能越界生成正文。");
        } else if (TARGET_CHAPTER_PROSE.equals(scope.targetType())
                || TARGET_CHAPTER_DRAFT.equals(scope.targetType())) {
            rule.append("当前范围是作者明确请求的本章正文草稿。");
        } else {
            rule.append("当前范围是本章正在讨论的问题，不顺带扩展到未被询问的任务。");
        }
        if (StringUtils.hasText(scope.targetReference())) {
            rule.append("作者指定的当前焦点是：").append(scope.targetReference().trim()).append("。");
        }
        return rule.toString();
    }

    private String authorityRule() {
        return "作者明确确定的内容必须遵守；作者最新消息高于旧讨论。助手此前提出的内容只是候选，"
                + "不能当成作者已经确认；已经否定的方向不得继续继承。";
    }

    private String conversationRule(ConversationReplyTaskInputV1 input) {
        return (input.consecutiveQuestionSuppressed()
                ? "上一轮已经提问或给出选项，本轮先综合作者回答并继续思考，不得再次连续出题。" : "")
                + (input.crossChapterRequested()
                ? "作者已明确要求跨章讨论，可以在当前请求范围内涉及后续章节。"
                : "只讨论当前章节，不主动设计、追问或预设下一章。")
                + "除非作者明确索要建议，不替作者做决定，也不用连续二选一代替共同思考。";
    }

    private String structuredInteractionRule(ConversationReplyTaskInputV1 input) {
        if (input.replyMode() == ReplyMode.COMPARE) {
            String optionCount = chineseCandidateCount(input.replyScope().maxCandidates());
            return "请只输出一个 JSON 对象，不要使用 Markdown 代码块。顶层必须且只能包含 content 和 interaction，二者缺一不可。"
                    + "content 是给作者看的比较说明，只能比较作者给出的候选，不得提议合并、混合或第三方案；"
                    + "interaction 表示单选问题：schemaVersion 固定为 1，type 使用 single_choice，"
                    + "questionId 是本问题稳定唯一标识，question 是作者看到的问题，options 包含"
                    + optionCount
                    + "实质不同的选项，每项包含 optionId、title、description 和 tradeoffs，allowCustom 必须为 true。";
        }
        if (input.replyMode() == ReplyMode.CLARIFY) {
            return "请只输出一个 JSON 对象，不要使用 Markdown 代码块。顶层必须且只能包含 content 和 interaction，二者缺一不可。"
                    + "content 是给作者看的简短说明；"
                    + "interaction 表示开放问题：schemaVersion 固定为 1，type 使用 open_question，"
                    + "questionId 是本问题稳定唯一标识。content 只说明当前指代不明确；question 只用一个问句询问所指对象，"
                    + "不得同时询问如何修改或是否需要新候选。options 为空数组，"
                    + "allowCustom 必须为 true。";
        }
        if (input.replyMode() == ReplyMode.EXPLORE) {
            return "直接进入讨论内容，不添加候选状态提示；作者没有要求多个方向时，不在结尾追加第二个方向。";
        }
        if (input.replyMode() == ReplyMode.CONVERGE) {
            return "用‘已经确定、仍待决定、已经否定’三个简短段落输出。每列一项，都必须能在作者原话中找到直接依据。"
                    + "作者没有亲口提出某个待决问题时，‘没有提到’不等于‘仍待决定’，该段只写‘暂无作者明确提出的待决内容’；"
                    + "作者没有亲口否定内容时，该段只写‘暂无作者明确否定的内容’。"
                    + "输出前再次检查：不得列出作者未提及的机制、过程、位置、距离、视角、人物或其他细节，"
                    + "不在结尾追加建议或问题。";
        }
        return "";
    }

    private String chineseNumber(int value) {
        return switch (value) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            case 4 -> "四";
            case 5 -> "五";
            default -> throw new IllegalStateException("conversation_reply 候选数量缺少自然语言映射");
        };
    }

    private String chineseCandidateCount(int value) {
        return value == 2 ? "两个" : chineseNumber(value) + "个";
    }
}
