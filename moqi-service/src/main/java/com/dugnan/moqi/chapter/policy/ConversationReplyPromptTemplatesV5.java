package com.dugnan.moqi.chapter.policy;

import org.springframework.util.StringUtils;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 维护章节讨论策略 V5 的精简模型可见模板。
 */
public final class ConversationReplyPromptTemplatesV5 {

    private static final String TARGET_CURRENT_FOCUS = "current_focus";
    private static final String TARGET_CHAPTER_PLAN = "chapter_plan";
    private static final String TARGET_CHAPTER_PROSE = "chapter_prose";
    private static final String TARGET_CHAPTER_DRAFT = "chapter_draft";
    private static final String CHANGES_ONLY = "changes_only";
    private static final String FACT_CORRECTION = "fact_correction";

    private ConversationReplyPromptTemplatesV5() {
    }

    /**
     * @return 共创回复的通用表达方式
     */
    public static String style() {
        return "使用自然、直接、容易理解的中文，回应作者当前提出的问题。";
    }

    /**
     * @param mode 讨论动作
     * @return 与动作对应的本轮目标
     */
    public static String action(ReplyMode mode) {
        return switch (mode) {
            case EXPLORE -> "本轮沿作者当前想法继续讨论；缺失信息会明显改变回答时再提问。";
            case CLARIFY -> "本轮只澄清一个会明显改变回答的关键歧义。结合对话说明无法确定的具体内容，"
                    + "再提出一个让作者容易直接回答的问题；得到答案后下一轮再讨论怎么改。";
            case COMPARE -> "本轮只比较作者提出或要求的方向。使用相同且具体的标准，"
                    + "从作者最新消息和已有对话识别实际比较对象，分别说明各自的效果和代价，把选择留给作者。"
                    + "不要要求程序预先给出对象数量，也不要补造作者没有提出的方向。"
                    + "作者明确写出的永久、不可逆、否定和未知边界必须保持不变，不能为了比较引入恢复、例外或反转；"
                    + "比较维度缺少对应资料时，说明它取决于后续设定，不补造血统、市场、组织或能力效果。";
            case CONVERGE -> "本轮直接、准确地复述作者要求总结的内容，以作者消息为准，保持原有的确定、待定或否定状态。"
                    + "作者没有明确缩小总结范围时，保留当前上下文中每一项仍然有效的决定、候选、纠正、否定和待决内容；"
                    + "回复深度只控制压缩和说明程度，不得用来删减事项。多项内容用分行或分段保持清楚，不把项目首尾粘连在一起。"
                    + "开头直接进入内容，不说明整理过程或是否新增事实。";
            case PLAN -> "本轮只整理作者请求的章节规划，不写正文。";
            case DRAFT -> "本轮只写作者请求范围内的正文草稿。";
        };
    }

    /**
     * @param mode 讨论动作
     * @param depth 信息覆盖程度
     * @return 与动作兼容的深度要求
     */
    public static String depth(ReplyMode mode, ReplyDepth depth) {
        if (mode == ReplyMode.CLARIFY) {
            return "澄清回复保持简短，只说明当前无法确定的内容并提出必要问题，不展开其他分析。";
        }
        return switch (depth) {
            case BRIEF -> mode == ReplyMode.CONVERGE
                    ? "本轮采用简洁总结：压缩每项内容的表达，不展开关系分析，但不得遗漏总结范围内的有效事项。"
                    : "本轮简洁回答，只保留理解当前答案所需的信息。";
            case BALANCED -> balanced(mode);
            case DEEP -> deep(mode);
        };
    }

    private static String balanced(ReplyMode mode) {
        return switch (mode) {
            case CONVERGE -> "本轮平衡整理主要内容及它们的直接关系，信息充分后收束。";
            case COMPARE -> "本轮平衡比较主要差别、直接影响和取舍，信息充分后收束。";
            default -> "本轮平衡回答，说明主要依据和直接影响，信息充分后收束。";
        };
    }

    private static String deep(ReplyMode mode) {
        return switch (mode) {
            case EXPLORE -> "本轮深入推演当前问题，把作者已经给出的前提如何影响当前问题说完整。"
                    + "沿一条连续变化解释这些前提之间的因果、取舍和影响，不列替代方向。"
                    + "不要自行补造作者未给出的机制、事件、证据、阶段或结局；"
                    + "作者未说明的触发条件、选择方式、先后顺序、强弱程度和可控性都保持未知，"
                    + "不用具体人物、事件或经历替作者补充例子，也不推定其他人物知道什么或采取什么行动。"
                    + "‘可能’和‘可以’只能标记现有前提的直接心理或关系影响，不能借此补入新前提或新过程。"
                    + "需要补充才能继续推演时，"
                    + "只指出仍缺少哪类信息，不给它预设答案。完成当前推演后直接结束，"
                    + "即使作者提到最终选择，也只分析已有代价形成的选择压力；"
                    + "不写‘如果选择甲就……、如果选择乙就……’的分支，不替作者给出具体选择或结局。"
                    + "不追加下一步建议、可继续讨论的角度、可选方向或不同结局。";
            case CLARIFY -> throw new IllegalStateException("澄清动作应使用兼容的精简深度模板");
            case COMPARE -> "本轮深入比较当前方向，说明关键差别、取舍和影响；不增加比较对象。"
                    + "对每个比较维度，只说明现有前提足以支持的差别；资料不足时明确说明取决于后续设定，"
                    + "不要用可能形成的血统、市场、组织、偏见、阶层规则或额外能力机制填满维度。";
            case CONVERGE -> "本轮深入总结，先准确复述各项决定，再说明它们共同产生的直接效果。"
                    + "事实名词只复用作者原话；关系说明只把作者已经说出的事项连起来，"
                    + "不补充作者未说出的背景、原因、证据或过程。不要把‘未知’改写成无法验证或缺少证据，"
                    + "也不要引入叙述者、情节安排或验证方式。共同效果只说明这些确定、未知和否定事项并置后，"
                    + "作者要求保留的状态如何继续成立；不解释缺少什么，也不推定人物如何理解。";
            case PLAN -> "本轮深入规划，说明当前规划的结构、依赖、取舍和影响，不写正文。";
            case DRAFT -> "本轮深入写作，在作者要求的正文范围内充分展开，不扩写其他情节。";
        };
    }

    /**
     * @param mode 讨论动作
     * @param count 作者请求的候选数量
     * @return 候选数量要求；不需要时为空
     */
    public static String candidateCount(ReplyMode mode, int count) {
        if (mode == ReplyMode.COMPARE) {
            return "比较对象只来自作者最新消息和已有对话；对象数量按实际识别结果，不增加其他方向。";
        }
        if (mode == ReplyMode.EXPLORE && count > 1) {
            return "本轮提供" + chineseCandidateCount(count) + "实质不同的方向。";
        }
        if (mode == ReplyMode.EXPLORE) {
            return "只沿当前回答方向展开，不在结尾追加多个可选分支。";
        }
        return "";
    }

    /**
     * @param scope 当前回复范围
     * @return 当前章节内的自然语言范围
     */
    public static String scope(ReplyScope scope) {
        if (FACT_CORRECTION.equals(scope.allowedChanges())) {
            return "只回复作者消息中更正后的事实，不重复更正前的错误，不追加确认语或其他说明。";
        }
        if (TARGET_CURRENT_FOCUS.equals(scope.targetType()) || CHANGES_ONLY.equals(scope.allowedChanges())) {
            return StringUtils.hasText(scope.targetReference())
                    ? "当前只处理本章中作者指定的焦点：" + scope.targetReference().trim() + "。"
                    : "当前范围是本章正在讨论的问题。";
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
     * @return 当前资料与作者确认内容的依据关系
     */
    public static String authority() {
        return "结合当前作品资料理解问题，以作者最新消息为准。"
                + "复述作者内容时，保留原有的否定和不确定措辞。";
    }

    /**
     * @param consecutiveQuestionSuppressed 是否应先回应上一轮答案
     * @param crossChapterRequested 作者是否明确请求跨章
     * @return 当前会话补充边界
     */
    public static String conversation(boolean consecutiveQuestionSuppressed, boolean crossChapterRequested) {
        return (consecutiveQuestionSuppressed ? "上一轮已经提问或给出选项，本轮先回应作者的回答。" : "")
                + (crossChapterRequested ? "作者已要求跨章，可以在当前问题内涉及相关章节。" : "");
    }

    /**
     * @param mode 讨论动作
     * @return 供服务端转换为交互控件的最小语义输出要求
     */
    public static String interaction(ReplyMode mode) {
        if (mode == ReplyMode.COMPARE) {
            return "为生成可选择的方向卡片，返回一个 JSON 对象，JSON 外不写文字。"
                    + "对象只使用中文语义：‘回复’写给作者看的完整比较，‘问题’写选择问题，"
                    + "‘选项’列出方向；每个方向只包含‘标题’、‘说明’和‘取舍’。"
                    + "能从对话识别比较对象时，‘选项’逐一对应实际对象；不能可靠识别时，不猜测对象，"
                    + "只返回‘回复’和‘问题’进行一次必要澄清。";
        }
        if (mode == ReplyMode.CLARIFY) {
            return "为生成可直接回答的问题，返回一个 JSON 对象，JSON 外不写文字。"
                    + "对象只包含‘回复’和‘问题’：‘回复’结合对话简短说明无法确定的具体内容，"
                    + "不重复提问；需要作者回答的内容只写在‘问题’中。‘问题’只确认这一歧义具体指向什么，"
                    + "只使用对话中已经出现的对象，不增加新的对象类别；不同时询问如何修改，"
                    + "也不要求作者补充新设定。";
        }
        return "";
    }

    /**
     * @param mode 讨论动作
     * @param depth 信息覆盖程度
     * @return 放在输出格式之后的最终越界检查；不需要时为空
     */
    public static String finalCheck(ReplyMode mode, ReplyDepth depth) {
        if (depth != ReplyDepth.DEEP) {
            return "";
        }
        return switch (mode) {
            case EXPLORE -> "输出前逐句检查：每个前提和影响都必须来自作者已经给出的内容。"
                    + "不得把作者未说明的对象属性、代价性质、知情程度、发生频率、人物行动或具体例子写进回答；"
                    + "缺少依据的连接保持未知，不用‘可能’或‘可以’补齐。";
            case COMPARE -> "输出前逐句检查：比较正文、说明和取舍中的每个事实或影响都必须由作者已给前提直接支持。"
                    + "不得补写人物会采取的行动、心理状态、能力表现、交易规则或恢复条件；"
                    + "缺少依据的维度明确写待定，不用‘可能’补齐。";
            default -> "";
        };
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
