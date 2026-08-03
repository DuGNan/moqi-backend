package com.dugnan.moqi.chapter.policy;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ReplyControlRequest;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 以确定性规则解析章节讨论模式、深度和最小推进范围。
 */
@Component
public class DefaultReplyPolicyResolver implements ReplyPolicyResolver {

    public static final String POLICY_VERSION = "chapter-reply-policy-v1";

    private static final String SCOPE_CONVERSATION = "conversation";
    private static final String SCOPE_CHAPTER = "chapter";
    private static final String SCOPE_WORK = "work";
    private static final String SCOPE_USER = "user";
    private static final String CONTROL_AUTO = "auto";
    private static final String CONTROL_MESSAGE_FEEDBACK = "message_feedback";
    private static final int MAX_SCOPE_TEXT_LENGTH = 200;
    private static final List<String> CONVERGENCE_TERMS =
            List.of("太多", "复杂", "改动太大", "简单一点", "简短", "收敛");
    private static final List<String> DRAFT_TERMS =
            List.of("写正文", "正文草稿", "写一段", "写片段", "生成正文", "开始写");
    private static final List<String> PLAN_TERMS =
            List.of("完整章节设计", "完整设计", "章纲", "大纲", "场景规划", "章节规划");
    private static final List<String> CONVERGE_TERMS =
            List.of("总结", "确认", "收束", "就这样", "采用", "撤回", "否定");
    private static final List<String> COMPARE_TERMS =
            List.of("选择", "选项", "方案", "比较", "几个", "更多", "一些");
    private static final List<String> AMBIGUOUS_TERMS =
            List.of("架空", "调整一下", "改一下", "换一个", "优化一下");
    private static final List<String> LOCAL_TERMS =
            List.of("只改", "只调整", "局部", "这个问题", "这一点", "第一章", "本轮仅讨论");
    private static final List<String> DEEP_TERMS =
            List.of("详细", "深入", "完整展开", "展开讲");
    private static final List<String> BRIEF_TERMS =
            List.of("简单一点", "简短");

    @Override
    public ResolvedReplyPolicy resolve(
            String content,
            ReplyControlRequest control,
            Map<String, ReplyDepth> inheritedDepths) {
        validateControl(control);
        String normalized = content == null ? "" : content.trim().toLowerCase(Locale.ROOT);
        boolean convergence = containsAny(normalized, CONVERGENCE_TERMS);
        ReplyMode mode = resolveMode(normalized, convergence);
        ReplyDepth automaticDepth = automaticDepth(normalized, mode, convergence);
        DepthSelection depthSelection = selectDepth(control, inheritedDepths, automaticDepth, normalized);
        if (convergence) {
            depthSelection = new DepthSelection(ReplyDepth.BRIEF, CONTROL_MESSAGE_FEEDBACK);
        }
        boolean localOnly = isCurrentOnly(control) || containsAny(normalized, LOCAL_TERMS);
        if (localOnly && !CONTROL_MESSAGE_FEEDBACK.equals(depthSelection.source())) {
            depthSelection = new DepthSelection(depthSelection.depth(), "message");
        }
        String scopeText = control == null ? null : trimToNull(control.scopeText());
        ReplyScope scope = new ReplyScope(
                primaryIntent(mode),
                localOnly ? "current_focus" : targetType(mode),
                scopeText,
                localOnly ? "changes_only" : allowedChanges(mode),
                mode == ReplyMode.COMPARE ? 3 : 1,
                mode == ReplyMode.DRAFT || mode == ReplyMode.PLAN);
        return new ResolvedReplyPolicy(
                mode,
                depthSelection.depth(),
                scope,
                depthSelection.source(),
                POLICY_VERSION,
                convergence);
    }

    private ReplyMode resolveMode(String content, boolean convergence) {
        if (convergence || containsAny(content, CONVERGE_TERMS)) {
            return ReplyMode.CONVERGE;
        }
        if (containsAny(content, DRAFT_TERMS)) {
            return ReplyMode.DRAFT;
        }
        if (containsAny(content, PLAN_TERMS)) {
            return ReplyMode.PLAN;
        }
        if (containsAny(content, AMBIGUOUS_TERMS) && !containsAny(content, LOCAL_TERMS)) {
            return ReplyMode.CLARIFY;
        }
        if (containsAny(content, COMPARE_TERMS)) {
            return ReplyMode.COMPARE;
        }
        return ReplyMode.CLARIFY;
    }

    private ReplyDepth automaticDepth(String content, ReplyMode mode, boolean convergence) {
        if (convergence || mode == ReplyMode.CONVERGE || mode == ReplyMode.COMPARE) {
            return ReplyDepth.BRIEF;
        }
        if (containsAny(content, DEEP_TERMS) || mode == ReplyMode.DRAFT) {
            return ReplyDepth.DEEP;
        }
        if (mode == ReplyMode.CLARIFY) {
            return ReplyDepth.BRIEF;
        }
        return ReplyDepth.BALANCED;
    }

    private DepthSelection selectDepth(
            ReplyControlRequest control,
            Map<String, ReplyDepth> inheritedDepths,
            ReplyDepth automaticDepth,
            String content) {
        ReplyDepth messageDepth = parseDepth(control == null ? null : control.depth());
        if (messageDepth != null) {
            return new DepthSelection(messageDepth, "message");
        }
        if (containsAny(content, DEEP_TERMS)) {
            return new DepthSelection(ReplyDepth.DEEP, "message");
        }
        if (containsAny(content, BRIEF_TERMS)) {
            return new DepthSelection(ReplyDepth.BRIEF, "message");
        }
        if (inheritedDepths != null) {
            for (String scope : List.of(SCOPE_CONVERSATION, SCOPE_CHAPTER, SCOPE_WORK, SCOPE_USER)) {
                ReplyDepth depth = inheritedDepths.get(scope);
                if (depth != null) {
                    return new DepthSelection(depth, scope);
                }
            }
        }
        return new DepthSelection(automaticDepth, "system");
    }

    private ReplyDepth parseDepth(String value) {
        if (!StringUtils.hasText(value) || CONTROL_AUTO.equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return ReplyDepth.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("replyControl.depth 仅支持 auto、brief、balanced、deep");
        }
    }

    private boolean isCurrentOnly(ReplyControlRequest control) {
        return control != null && "current_only".equalsIgnoreCase(control.scopeMode());
    }

    private void validateControl(ReplyControlRequest control) {
        if (control == null) {
            return;
        }
        if (StringUtils.hasText(control.scopeMode())
                && !CONTROL_AUTO.equalsIgnoreCase(control.scopeMode())
                && !"current_only".equalsIgnoreCase(control.scopeMode())) {
            throw new IllegalArgumentException("replyControl.scopeMode 仅支持 auto、current_only");
        }
        if (control.scopeText() != null && control.scopeText().trim().length() > MAX_SCOPE_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "replyControl.scopeText 不能超过 " + MAX_SCOPE_TEXT_LENGTH + " 个字符");
        }
    }

    private boolean containsAny(String content, List<String> terms) {
        return terms.stream().anyMatch(content::contains);
    }

    private String primaryIntent(ReplyMode mode) {
        return switch (mode) {
            case CLARIFY -> "clarify_direction";
            case COMPARE -> "compare_candidates";
            case CONVERGE -> "converge_consensus";
            case PLAN -> "build_plan";
            case DRAFT -> "write_draft";
        };
    }

    private String targetType(ReplyMode mode) {
        return switch (mode) {
            case PLAN -> "chapter_plan";
            case DRAFT -> "chapter_prose";
            default -> "current_discussion";
        };
    }

    private String allowedChanges(ReplyMode mode) {
        return switch (mode) {
            case CLARIFY -> "question_only";
            case COMPARE -> "candidate_summaries";
            case CONVERGE -> "confirmed_and_pending_summary";
            case PLAN -> "requested_plan";
            case DRAFT -> "requested_draft";
        };
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record DepthSelection(ReplyDepth depth, String source) {
    }
}
