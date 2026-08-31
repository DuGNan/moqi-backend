package com.dugnan.moqi.chapter.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    public static final String POLICY_VERSION = "chapter-reply-policy-v5";

    private static final String SCOPE_CONVERSATION = "conversation";
    private static final String SCOPE_CHAPTER = "chapter";
    private static final String SCOPE_WORK = "work";
    private static final String SCOPE_USER = "user";
    private static final String CONTROL_AUTO = "auto";
    private static final String CHINESE_FULL_STOP = "。";
    private static final String WITHDRAW_TERM = "撤回";
    private static final int MAX_SCOPE_TEXT_LENGTH = 200;
    private static final int MIN_MIXED_DEPTH_COUNT = 2;
    private static final Pattern CORRECTION_PREFIX_PATTERN = Pattern.compile("^[^：:]{0,24}[：:]\\s*");
    private static final Pattern CORRECTION_INSTRUCTION_SUFFIX_PATTERN = Pattern.compile(
            "[，,；;。]?\\s*(?:不要|不需要|无需|不必|不要求)(?:重建|重新推演).*$");
    private static final Pattern CANDIDATE_COUNT_PATTERN = Pattern.compile(
            "([1-5一二两三四五])\\s*(?:个|种|条)?\\s*"
                    + "(?:候选|方案|方向|选项|开篇|设定|场景|写法|版本)");
    private static final Pattern MODIFIED_CANDIDATE_COUNT_PATTERN = Pattern.compile(
            "([1-5一二两三四五])\\s*(?:个|种|条)\\s*"
                    + "(?:可继续讨论的|不同的|备选的|可选的|具体的|独立的|明显不同的)\\s*"
                    + "(?:候选|方案|方向|选项)");
    private static final Pattern MULTI_DIMENSION_PATTERN = Pattern.compile(
            "(?:[2-5二两三四五]\\s*个?(?:维度|方面)|多个(?:维度|方面)|多维|各个(?:维度|方面)|相互影响|联动影响)");
    private static final List<String> CONVERGENCE_TERMS =
            List.of("内容太多", "方案太多", "改动太大", "有点复杂了", "太复杂了", "收敛一下");
    private static final List<String> DRAFT_TERMS =
            List.of("写正文", "正文草稿", "写一段", "写一句", "写片段", "写场景", "写完整场景", "生成正文", "开始写");
    private static final List<String> PLAN_TERMS =
            List.of("完整章节设计", "完整设计", "章纲", "大纲", "场景规划", "章节规划");
    private static final List<String> CONVERGE_TERMS =
            List.of("总结", "整理已确认", "整理我们已经", "收束", "归纳");
    private static final List<String> CANDIDATE_TERMS =
            List.of("给我选项", "给我候选", "给我几个方案", "给我更多", "有哪些选项",
                    "没有思路", "没想法");
    private static final List<String> COMPARE_TERMS =
            List.of("比较", "对比", "差别", "区别", "优劣", "哪个好");
    private static final List<String> AMBIGUOUS_TERMS =
            List.of("架空", "调整一下", "改一下", "换一个", "优化一下");
    private static final List<String> LOCAL_TERMS =
            List.of("只改", "只把", "只调整", "局部", "这个问题", "这一点", "第一章", "本轮仅讨论");
    private static final List<String> DEEP_TERMS =
            List.of("详细", "深入", "完整展开", "完整推演", "系统分析", "展开讲");
    private static final List<String> BRIEF_TERMS =
            List.of("简洁", "简要", "简单说", "简短", "只给结论", "不要展开", "一句话", "写一句");
    private static final List<String> BALANCED_TERMS =
            List.of("平衡一点", "平衡深度", "按平衡", "适中展开", "适中一点", "适中回复");
    private static final List<String> DEEP_EXCLUSION_TERMS =
            List.of("不要深入", "不用深入", "无需深入", "不必深入", "别深入", "不要太深入");
    private static final List<String> BRIEF_EXCLUSION_TERMS =
            List.of("不要简略", "不要简洁", "不用简略", "无需简略", "别太简短", "不要太简短");
    private static final List<String> DEPTH_REVISION_TERMS =
            List.of("算了", "还是", "改成", "改为", "最后", "最终");
    private static final List<String> LOCAL_FACT_CHANGE_TERMS =
            List.of("只纠正", "纠正一点", "只确认", "确认这一点", "只撤回", "撤回这个", "撤回该",
                    "撤回这一", "只改这一点", "只改这个事实");
    private static final List<String> REBUILD_IMPACT_TERMS =
            List.of("重建影响", "重新推演", "连锁影响", "后续影响", "重新分析");
    private static final List<String> SKIP_REBUILD_IMPACT_TERMS =
            List.of("不要重建", "不需要重建", "无需重建", "不必重建", "不要求重建",
                    "不要重新推演", "不需要重新推演", "无需重新推演", "不必重新推演");
    private static final List<String> COMPLETE_ANALYSIS_TERMS =
            List.of("完整", "系统", "推演", "分析");
    private static final List<String> CROSS_CHAPTER_TERMS =
            List.of("第二章", "下一章", "后续章节", "跨章", "后面几章");

    @Override
    public ResolvedReplyPolicy resolve(
            String content,
            ReplyControlRequest control,
            Map<String, ReplyDepth> inheritedDepths) {
        return resolve(content, control, inheritedDepths, ReplyConversationSignals.empty());
    }

    @Override
    public ResolvedReplyPolicy resolve(
            String content,
            ReplyControlRequest control,
            Map<String, ReplyDepth> inheritedDepths,
            ReplyConversationSignals signals) {
        validateControl(control);
        String normalized = content == null ? "" : content.trim().toLowerCase(Locale.ROOT);
        boolean convergenceRequested = containsAny(normalized, CONVERGENCE_TERMS);
        boolean localFactChange = isLocalFactChange(normalized);
        boolean pureFactCorrection = localFactChange && !requestsImpactRebuild(normalized);
        Integer requestedCandidateCount = requestedCandidateCount(normalized);
        boolean candidatesRequested = containsAny(normalized, CANDIDATE_TERMS)
                || requestedCandidateCount != null && requestedCandidateCount > 1;
        ReplyConversationSignals effectiveSignals = signals == null ? ReplyConversationSignals.empty() : signals;
        ReplyMode mode = resolveMode(normalized, convergenceRequested, candidatesRequested, effectiveSignals);
        boolean consecutiveQuestionSuppressed = mode == ReplyMode.EXPLORE
                && isShortAnswer(normalized) && suppressConsecutiveQuestion(effectiveSignals);
        ReplyDepth automaticDepth = automaticDepth(normalized, pureFactCorrection);
        DepthSelection requestedDepth = selectDepth(
                control, inheritedDepths, automaticDepth, normalized, mode, effectiveSignals);
        ReplyDepth effectiveDepth = mode == ReplyMode.CLARIFY ? ReplyDepth.BRIEF : requestedDepth.depth();
        String controlSource = mode == ReplyMode.CLARIFY ? "clarification" : requestedDepth.source();
        ReplyDepth deferredDepth = mode == ReplyMode.CLARIFY && requestedDepth.depth() != ReplyDepth.BRIEF
                ? requestedDepth.depth() : null;
        boolean localOnly = localFactChange || isCurrentOnly(control) || containsAny(normalized, LOCAL_TERMS);
        String scopeText = pureFactCorrection
                ? correctionReference(content)
                : control == null ? null : trimToNull(control.scopeText());
        int candidateCount = candidateCount(mode, candidatesRequested, requestedCandidateCount);
        ReplyScope scope = new ReplyScope(
                primaryIntent(mode),
                localOnly ? "current_focus" : targetType(mode),
                scopeText,
                pureFactCorrection ? "fact_correction" : localOnly ? "changes_only" : allowedChanges(mode),
                candidateCount,
                mode == ReplyMode.DRAFT || mode == ReplyMode.PLAN);
        return new ResolvedReplyPolicy(
                mode,
                effectiveDepth,
                scope,
                controlSource,
                POLICY_VERSION,
                convergenceRequested && mode == ReplyMode.CONVERGE,
                effectiveSignals.previousMode(),
                consecutiveQuestionSuppressed,
                containsAny(normalized, CROSS_CHAPTER_TERMS),
                deferredDepth);
    }

    private ReplyMode resolveMode(
            String content,
            boolean convergenceRequested,
            boolean candidatesRequested,
            ReplyConversationSignals signals) {
        ReplyMode explicitMode = lastExplicitMode(content, candidatesRequested);
        if (explicitMode != null) {
            return explicitMode;
        }
        if (containsAny(content, AMBIGUOUS_TERMS) && !containsAny(content, LOCAL_TERMS)) {
            return ReplyMode.CLARIFY;
        }
        if (convergenceRequested) {
            return ReplyMode.CONVERGE;
        }
        if (isShortAnswer(content) && suppressConsecutiveQuestion(signals)) {
            return ReplyMode.EXPLORE;
        }
        return ReplyMode.EXPLORE;
    }

    private ReplyMode lastExplicitMode(String content, boolean candidatesRequested) {
        List<ModeDirective> directives = new ArrayList<>();
        addModeDirectives(directives, content, DRAFT_TERMS, ReplyMode.DRAFT);
        addModeDirectives(directives, content, PLAN_TERMS, ReplyMode.PLAN);
        addModeDirectives(directives, content, CONVERGE_TERMS, ReplyMode.CONVERGE);
        addModeDirectives(directives, content, COMPARE_TERMS, ReplyMode.COMPARE);
        if (candidatesRequested) {
            addModeDirectives(directives, content, CANDIDATE_TERMS, ReplyMode.EXPLORE);
        }
        return directives.stream()
                .max(Comparator.comparingInt(ModeDirective::position))
                .map(ModeDirective::mode)
                .orElse(null);
    }

    private void addModeDirectives(
            List<ModeDirective> directives,
            String content,
            List<String> terms,
            ReplyMode mode) {
        for (String term : terms) {
            int position = content.lastIndexOf(term);
            if (position >= 0) {
                directives.add(new ModeDirective(position, mode));
            }
        }
    }

    private boolean isShortAnswer(String content) {
        return content.length() <= 24;
    }

    private boolean suppressConsecutiveQuestion(ReplyConversationSignals signals) {
        return signals.previousMode() == ReplyMode.CLARIFY
                || signals.previousMode() == ReplyMode.COMPARE
                || signals.previousAssistantAskedQuestion()
                || signals.previousAssistantOfferedOptions();
    }

    private ReplyDepth automaticDepth(String content, boolean pureFactCorrection) {
        if (pureFactCorrection) {
            return ReplyDepth.BRIEF;
        }
        if (requestsMultiDimensionalAnalysis(content)) {
            return ReplyDepth.DEEP;
        }
        return null;
    }

    private boolean isLocalFactChange(String content) {
        return containsAny(content, LOCAL_FACT_CHANGE_TERMS);
    }

    private String correctionReference(String content) {
        String reference = trimToNull(content);
        if (reference == null) {
            return null;
        }
        if (!reference.contains(WITHDRAW_TERM)) {
            Matcher prefixMatcher = CORRECTION_PREFIX_PATTERN.matcher(reference);
            if (prefixMatcher.find()) {
                reference = reference.substring(prefixMatcher.end()).trim();
            }
        }
        reference = CORRECTION_INSTRUCTION_SUFFIX_PATTERN.matcher(reference).replaceFirst("").trim();
        if (reference.endsWith(CHINESE_FULL_STOP)) {
            reference = reference.substring(0, reference.length() - 1).trim();
        }
        return StringUtils.hasText(reference) && reference.length() <= MAX_SCOPE_TEXT_LENGTH ? reference : null;
    }

    private boolean requestsImpactRebuild(String content) {
        if (containsAny(content, SKIP_REBUILD_IMPACT_TERMS)) {
            return false;
        }
        return containsAny(content, REBUILD_IMPACT_TERMS)
                || content.contains("重建") && content.contains("影响");
    }

    private boolean requestsMultiDimensionalAnalysis(String content) {
        return MULTI_DIMENSION_PATTERN.matcher(content).find()
                && containsAny(content, COMPLETE_ANALYSIS_TERMS);
    }

    private DepthSelection selectDepth(
            ReplyControlRequest control,
            Map<String, ReplyDepth> inheritedDepths,
            ReplyDepth automaticDepth,
            String content,
            ReplyMode mode,
            ReplyConversationSignals signals) {
        ReplyDepth messageRequestedDepth = messageRequestedDepth(content);
        if (messageRequestedDepth != null) {
            return new DepthSelection(messageRequestedDepth, "message");
        }
        ReplyDepth selectedDepth = parseDepth(control == null ? null : control.depth());
        if (selectedDepth != null) {
            return new DepthSelection(selectedDepth, "request");
        }
        if (mode == ReplyMode.EXPLORE
                && signals.directClarificationAnswer()
                && signals.deferredDepth() != null) {
            return new DepthSelection(signals.deferredDepth(), "clarification");
        }
        if (automaticDepth != null) {
            return new DepthSelection(automaticDepth, "system");
        }
        if (inheritedDepths != null) {
            for (String scope : List.of(SCOPE_CONVERSATION, SCOPE_CHAPTER, SCOPE_WORK, SCOPE_USER)) {
                ReplyDepth depth = inheritedDepths.get(scope);
                if (depth != null) {
                    return new DepthSelection(depth, scope);
                }
            }
        }
        return new DepthSelection(ReplyDepth.BALANCED, "system");
    }

    private ReplyDepth messageRequestedDepth(String content) {
        List<DepthDirective> directives = depthDirectives(content);
        if (directives.isEmpty()) {
            return null;
        }
        directives.sort(Comparator.comparingInt(DepthDirective::position));
        if (isScopedMixedDepth(content, directives)) {
            return directives.stream()
                    .map(DepthDirective::selectedDepth)
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.comparingInt(this::depthRank))
                    .orElse(ReplyDepth.BALANCED);
        }
        Set<ReplyDepth> excluded = new HashSet<>();
        ReplyDepth selected = null;
        for (DepthDirective directive : directives) {
            if (directive.excludedDepth() != null) {
                excluded.add(directive.excludedDepth());
                if (selected == directive.excludedDepth()) {
                    selected = null;
                }
                continue;
            }
            selected = directive.selectedDepth();
            excluded.remove(selected);
        }
        return selected == null && !excluded.isEmpty() ? ReplyDepth.BALANCED : selected;
    }

    private List<DepthDirective> depthDirectives(String content) {
        List<DepthDirective> directives = new ArrayList<>();
        List<TextRange> exclusions = new ArrayList<>();
        addExclusionDirectives(directives, exclusions, content, DEEP_EXCLUSION_TERMS, ReplyDepth.DEEP);
        addExclusionDirectives(directives, exclusions, content, BRIEF_EXCLUSION_TERMS, ReplyDepth.BRIEF);
        addDepthDirectives(directives, exclusions, content, BRIEF_TERMS, ReplyDepth.BRIEF);
        addDepthDirectives(directives, exclusions, content, BALANCED_TERMS, ReplyDepth.BALANCED);
        addDepthDirectives(directives, exclusions, content, DEEP_TERMS, ReplyDepth.DEEP);
        return directives;
    }

    private void addExclusionDirectives(
            List<DepthDirective> directives,
            List<TextRange> exclusions,
            String content,
            List<String> terms,
            ReplyDepth excludedDepth) {
        for (String term : terms) {
            int from = 0;
            int position;
            while ((position = content.indexOf(term, from)) >= 0) {
                directives.add(new DepthDirective(position, null, excludedDepth));
                exclusions.add(new TextRange(position, position + term.length()));
                from = position + term.length();
            }
        }
    }

    private void addDepthDirectives(
            List<DepthDirective> directives,
            List<TextRange> exclusions,
            String content,
            List<String> terms,
            ReplyDepth selectedDepth) {
        for (String term : terms) {
            int from = 0;
            int position;
            while ((position = content.indexOf(term, from)) >= 0) {
                int matchPosition = position;
                if (exclusions.stream().noneMatch(range -> range.contains(matchPosition))) {
                    directives.add(new DepthDirective(position, selectedDepth, null));
                }
                from = position + term.length();
            }
        }
    }

    private boolean isScopedMixedDepth(String content, List<DepthDirective> directives) {
        if (containsAny(content, DEPTH_REVISION_TERMS)) {
            return false;
        }
        long distinctDepths = directives.stream()
                .map(DepthDirective::selectedDepth)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
        if (distinctDepths < MIN_MIXED_DEPTH_COUNT) {
            return false;
        }
        return content.contains("，") || content.contains(",") || content.contains("；")
                || content.contains(";") || content.contains("再") || content.contains("分别");
    }

    private int depthRank(ReplyDepth depth) {
        return switch (depth) {
            case BRIEF -> 1;
            case BALANCED -> 2;
            case DEEP -> 3;
        };
    }

    private int candidateCount(
            ReplyMode mode,
            boolean candidatesRequested,
            Integer requestedCandidateCount) {
        if (mode == ReplyMode.COMPARE) {
            return 1;
        }
        if (requestedCandidateCount != null) {
            return requestedCandidateCount;
        }
        return candidatesRequested ? 3 : 1;
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

    private Integer requestedCandidateCount(String content) {
        Matcher matcher = CANDIDATE_COUNT_PATTERN.matcher(content);
        if (!matcher.find()) {
            matcher = MODIFIED_CANDIDATE_COUNT_PATTERN.matcher(content);
            if (!matcher.find()) {
                return null;
            }
        }
        return switch (matcher.group(1)) {
            case "1", "一" -> 1;
            case "2", "二", "两" -> 2;
            case "3", "三" -> 3;
            case "4", "四" -> 4;
            case "5", "五" -> 5;
            default -> throw new IllegalStateException("候选数量映射不完整");
        };
    }

    private String primaryIntent(ReplyMode mode) {
        return switch (mode) {
            case EXPLORE -> "explore_direction";
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
            case EXPLORE -> "discussion_expansion";
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

    private record DepthDirective(int position, ReplyDepth selectedDepth, ReplyDepth excludedDepth) {
    }

    private record ModeDirective(int position, ReplyMode mode) {
    }

    private record TextRange(int start, int end) {

        private boolean contains(int position) {
            return position >= start && position < end;
        }
    }
}
