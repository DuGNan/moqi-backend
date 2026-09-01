package com.dugnan.moqi.chapter.policy;

import org.springframework.util.StringUtils;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 固定维护章节讨论策略 V4 的模型可见自然语言模板。
 */
public final class ConversationReplyPromptTemplatesV4 {

    private static final String TARGET_CURRENT_FOCUS = "current_focus";
    private static final String TARGET_CHAPTER_PLAN = "chapter_plan";
    private static final String TARGET_CHAPTER_PROSE = "chapter_prose";
    private static final String TARGET_CHAPTER_DRAFT = "chapter_draft";
    private static final String CHANGES_ONLY = "changes_only";
    private static final String FACT_CORRECTION = "fact_correction";

    private ConversationReplyPromptTemplatesV4() {
    }

    /**
     * 共创身份与表达方式。
     *
     * @return 通用身份模板
     */
    public static String identity() {
        return "你是墨契的章节共创搭档。使用自然、直接、容易理解的中文，回应作者当前提出的问题。";
    }

    /**
     * 本轮讨论动作。
     *
     * @param mode 讨论动作
     * @return 动作模板
     */
    public static String action(ReplyMode mode) {
        return switch (mode) {
            case EXPLORE -> "本轮沿作者当前想法继续讨论。作者没有要求多个方向时，只推进当前方向；"
                    + "缺失信息会明显改变回答时再提问。";
            case CLARIFY -> "本轮只澄清一个会明显改变回答的关键歧义；作者指代不清时，只问‘具体指的是哪一项’，"
                    + "得到答案后下一轮再讨论怎么改。";
            case COMPARE -> "本轮只比较作者提出或要求的方向。使用相同且具体的标准，"
                    + "分别说明各自的效果和代价，把选择留给作者。";
            case CONVERGE -> "本轮只整理作者要求总结的内容。每项结论必须有作者原话或明确采纳作为依据，"
                    + "作者的否定保持为否定句，不增加新设定、建议或问题。";
            case PLAN -> "本轮只整理作者请求的章节规划，不写正文。";
            case DRAFT -> "本轮只写作者请求范围内的正文草稿。";
        };
    }

    /**
     * 本轮信息覆盖程度。
     *
     * @param mode 本轮讨论动作
     * @param depth 回复深度
     * @return 深度模板
     */
    public static String depth(ReplyMode mode, ReplyDepth depth) {
        return switch (depth) {
            case BRIEF -> "本轮简洁回答，只保留理解当前答案所需的信息。";
            case BALANCED -> "本轮平衡回答，说明主要依据和直接影响，信息充分后收束。";
            case DEEP -> deep(mode);
        };
    }

    private static String deep(ReplyMode mode) {
        return switch (mode) {
            case EXPLORE -> "本轮深入推演当前问题，把作者已经给出的前提如何影响当前问题说完整。"
                    + "沿一条连续变化解释这些前提之间的因果、取舍和影响，不列替代方向。"
                    + "需要补充才能继续推演时，把补充写成‘可以’或‘可能’的待讨论内容，"
                    + "不当作作者已经确定的事实。";
            case CLARIFY -> "本轮深入澄清这个歧义及其影响，但只提出解决它所需的问题。";
            case COMPARE -> "本轮深入比较当前方向，说明关键差别、取舍和影响；不增加比较对象。";
            case CONVERGE -> "本轮深入总结，完整整理作者已经确认的信息及其直接关系和边界，不增加事实。";
            case PLAN -> "本轮深入规划，说明当前规划的结构、依赖、取舍和影响，不写正文。";
            case DRAFT -> "本轮深入写作，在作者要求的正文范围内充分展开，不扩写其他情节。";
        };
    }

    /**
     * 作者请求的候选数量。
     *
     * @param mode 讨论动作
     * @param count 候选数量
     * @return 候选数量模板；当前动作不需要数量要求时返回空字符串
     */
    public static String candidateCount(ReplyMode mode, int count) {
        if (mode == ReplyMode.COMPARE) {
            return "本轮比较" + chineseCandidateCount(count) + "方向，不增加其他方向。";
        }
        if (mode == ReplyMode.EXPLORE && count > 1) {
            return "本轮提供" + chineseCandidateCount(count) + "实质不同的方向。";
        }
        return "";
    }

    /**
     * 当前章节讨论范围。
     *
     * @param scope 回复范围
     * @return 范围模板
     */
    public static String scope(ReplyScope scope) {
        if (FACT_CORRECTION.equals(scope.allowedChanges())) {
            return StringUtils.hasText(scope.targetReference())
                    ? "作者这次明确表达的是：‘" + scope.targetReference().trim() + "’。本轮只复述这项变更。"
                    : "本轮只复述作者刚刚明确表达的变更。";
        }
        if (TARGET_CURRENT_FOCUS.equals(scope.targetType()) || CHANGES_ONLY.equals(scope.allowedChanges())) {
            return StringUtils.hasText(scope.targetReference())
                    ? "当前只处理作者指定的焦点：" + scope.targetReference().trim() + "。"
                    : "当前只处理作者指定的焦点。";
        }
        if (TARGET_CHAPTER_PLAN.equals(scope.targetType())) {
            return "当前范围是本章规划。";
        }
        if (TARGET_CHAPTER_PROSE.equals(scope.targetType())
                || TARGET_CHAPTER_DRAFT.equals(scope.targetType())) {
            return "当前范围是作者请求的本章正文。";
        }
        return "当前范围是本章正在讨论的问题。";
    }

    /**
     * 作者事实、助手候选和否定内容的权威边界。
     *
     * @return 权威边界模板
     */
    public static String authority() {
        return "以当前作品资料和作者已经明确确定的内容为依据；作者最新消息优先。";
    }

    /**
     * 连续会话和跨章节范围。
     *
     * @param consecutiveQuestionSuppressed 是否抑制连续提问
     * @param crossChapterRequested 是否允许跨章节讨论
     * @return 会话模板
     */
    public static String conversation(
            boolean consecutiveQuestionSuppressed,
            boolean crossChapterRequested) {
        return (consecutiveQuestionSuppressed
                ? "上一轮已经提问或给出选项，本轮先回应作者的回答。" : "")
                + (crossChapterRequested
                ? "作者已要求跨章，可以在当前问题内涉及相关章节。"
                : "只讨论当前章节。");
    }

    /**
     * 结构化交互输出契约。
     *
     * @param mode 讨论动作
     * @param candidateCount 候选数量
     * @return 输出契约；当前动作不需要特殊格式时返回空字符串
     */
    public static String interaction(ReplyMode mode, int candidateCount) {
        if (mode == ReplyMode.COMPARE) {
            return "只输出一个 JSON 对象，不使用 Markdown。顶层仅含 content 和 interaction。"
                    + "content 是给作者看的比较；interaction 包含 schemaVersion=1、type=single_choice、"
                    + "questionId、question、options 和 allowCustom=true。options 包含"
                    + chineseCandidateCount(candidateCount)
                    + "实质不同的选项，每项包含 optionId、title、description 和 tradeoffs。";
        }
        if (mode == ReplyMode.CLARIFY) {
            return "只输出一个 JSON 对象，不使用 Markdown。顶层仅含 content 和 interaction。"
                    + "content 只说明当前指代不明确，不宣称作者已经撤回或否定任何建议；"
                    + "interaction 包含 schemaVersion=1、type=open_question、questionId、question、空 options 和 allowCustom=true，"
                    + "question 只询问作者具体指的是哪一项。";
        }
        if (mode == ReplyMode.CONVERGE) {
            return "自然整理作者已经明确确定的内容；仍待决定或已经否定的内容只在与本轮总结直接相关且有明确依据时说明。"
                    + "没有依据的类别不输出，也不使用固定段落或占位语。";
        }
        return "";
    }

    private static String chineseCandidateCount(int value) {
        return value == 2 ? "两个" : chineseNumber(value) + "个";
    }

    private static String chineseNumber(int value) {
        return switch (value) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            case 4 -> "四";
            case 5 -> "五";
            default -> throw new IllegalStateException("conversation_reply 候选数量缺少自然语言映射");
        };
    }
}
