package com.dugnan.moqi.chapter.consensus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.Decision;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.ReaderProgress;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.StateChange;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 校验并规范化章节结构化共识 V1。
 */
@Component
public class ChapterConsensusValidator {

    private static final int SCHEMA_VERSION = 1;
    private static final String STATUS_CONFIRMED = "confirmed";

    private static final int MAIN_TEXT_MAX_LENGTH = 2000;

    private static final int DECISION_TITLE_MAX_LENGTH = 100;

    private static final int DECISION_KEY_MAX_LENGTH = 64;

    private static final int DECISION_PROMPT_MAX_LENGTH = 1000;

    private static final int DECISION_SUMMARY_MAX_LENGTH = 2000;

    private static final int BOUNDARY_MAX_COUNT = 20;

    private static final int BOUNDARY_MAX_LENGTH = 500;

    private static final int DECISION_MAX_COUNT = 20;

    private static final int SOURCE_MAX_COUNT_PER_DECISION = 50;

    private static final int SOURCE_MAX_COUNT_TOTAL = 200;

    private static final Pattern DECISION_KEY_PATTERN =
            Pattern.compile("[a-z][a-z0-9_]{0," + (DECISION_KEY_MAX_LENGTH - 1) + "}");

    private static final Set<String> DECISION_STATUSES =
            Set.of(STATUS_CONFIRMED, "candidates", "discussing", "pending");

    /**
     * 校验并规范化可保存的共识草稿。
     *
     * @param content 原始共识
     * @return 规范化后的共识
     */
    public ChapterConsensusContentV1 normalizeDraft(ChapterConsensusContentV1 content) {
        if (content == null) {
            throw invalid("章节共识不能为空");
        }
        if (!Integer.valueOf(SCHEMA_VERSION).equals(content.schemaVersion())) {
            throw invalid("章节共识 schemaVersion 必须为 1");
        }

        String chapterTask = normalizeText(content.chapterTask(), MAIN_TEXT_MAX_LENGTH, "chapterTask");
        StateChange stateChange = content.stateChange() == null
                ? new StateChange("", "")
                : new StateChange(
                        normalizeText(content.stateChange().from(), MAIN_TEXT_MAX_LENGTH, "stateChange.from"),
                        normalizeText(content.stateChange().to(), MAIN_TEXT_MAX_LENGTH, "stateChange.to"));
        String keyPush = normalizeText(content.keyPush(), MAIN_TEXT_MAX_LENGTH, "keyPush");
        ReaderProgress readerProgress = content.readerProgress() == null
                ? new ReaderProgress("", "")
                : new ReaderProgress(
                        normalizeText(content.readerProgress().payoff(), MAIN_TEXT_MAX_LENGTH, "readerProgress.payoff"),
                        normalizeText(
                                content.readerProgress().openQuestion(),
                                MAIN_TEXT_MAX_LENGTH,
                                "readerProgress.openQuestion"));

        List<String> writingBoundaries = normalizeBoundaries(content.writingBoundaries());
        List<Decision> decisions = normalizeDecisions(content.decisions());
        return new ChapterConsensusContentV1(
                SCHEMA_VERSION,
                chapterTask,
                stateChange,
                keyPush,
                readerProgress,
                writingBoundaries,
                decisions);
    }

    /**
     * 校验共识是否可以被用户确认为正式 Brief。
     *
     * @param content 原始共识
     * @return 规范化且可确认的共识
     */
    public ChapterConsensusContentV1 requireConfirmable(ChapterConsensusContentV1 content) {
        ChapterConsensusContentV1 normalized = normalizeDraft(content);
        if (normalized.chapterTask().isBlank()
                || normalized.stateChange().from().isBlank()
                || normalized.stateChange().to().isBlank()
                || normalized.keyPush().isBlank()) {
            throw confirmationBlocked("本章任务、状态变化和关键推进必须完整后才能确认");
        }
        boolean hasUnresolvedRequiredDecision = normalized.decisions().stream()
                .anyMatch(decision -> decision.required() && !STATUS_CONFIRMED.equals(decision.status()));
        if (hasUnresolvedRequiredDecision) {
            throw confirmationBlocked("仍有必要待决项未确认");
        }
        return normalized;
    }

    /**
     * 规范化写作边界。
     *
     * @param boundaries 原始写作边界
     * @return 去重后的写作边界
     */
    private List<String> normalizeBoundaries(List<String> boundaries) {
        if (boundaries == null || boundaries.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String boundary : boundaries) {
            String value = normalizeText(boundary, BOUNDARY_MAX_LENGTH, "writingBoundaries");
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        if (normalized.size() > BOUNDARY_MAX_COUNT) {
            throw invalid("writingBoundaries 不能超过 " + BOUNDARY_MAX_COUNT + " 项");
        }
        return List.copyOf(normalized);
    }

    /**
     * 规范化待决列表。
     *
     * @param decisions 原始待决列表
     * @return 规范化后的待决列表
     */
    private List<Decision> normalizeDecisions(List<Decision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return List.of();
        }
        if (decisions.size() > DECISION_MAX_COUNT) {
            throw invalid("decisions 不能超过 " + DECISION_MAX_COUNT + " 项");
        }

        List<Decision> normalized = new ArrayList<>(decisions.size());
        Set<String> decisionKeys = new LinkedHashSet<>();
        int sourceCount = 0;
        for (Decision decision : decisions) {
            if (decision == null) {
                throw invalid("decision 不能为空");
            }
            Decision normalizedDecision = normalizeDecision(decision);
            if (!decisionKeys.add(normalizedDecision.key())) {
                throw invalid("decision key 不能重复：" + normalizedDecision.key());
            }
            sourceCount += normalizedDecision.sourceMessageIds().size();
            if (sourceCount > SOURCE_MAX_COUNT_TOTAL) {
                throw invalid("全部 sourceMessageIds 不能超过 " + SOURCE_MAX_COUNT_TOTAL + " 项");
            }
            normalized.add(normalizedDecision);
        }
        return List.copyOf(normalized);
    }

    /**
     * 规范化单个待决。
     *
     * @param decision 原始待决
     * @return 规范化后的待决
     */
    private Decision normalizeDecision(Decision decision) {
        String key = normalizeText(decision.key(), DECISION_KEY_MAX_LENGTH, "decision.key");
        if (!DECISION_KEY_PATTERN.matcher(key).matches()) {
            throw invalid("decision.key 必须使用小写英文、数字或下划线，并以英文开头");
        }
        String title = normalizeText(decision.title(), DECISION_TITLE_MAX_LENGTH, "decision.title");
        if (title.isBlank()) {
            throw invalid("decision.title 不能为空");
        }
        String status = normalizeText(decision.status(), 20, "decision.status");
        if (!DECISION_STATUSES.contains(status)) {
            throw invalid("decision.status 不受支持：" + status);
        }
        String prompt = normalizeText(decision.prompt(), DECISION_PROMPT_MAX_LENGTH, "decision.prompt");
        String candidateSummary = normalizeText(
                decision.candidateSummary(),
                DECISION_SUMMARY_MAX_LENGTH,
                "decision.candidateSummary");
        if (!STATUS_CONFIRMED.equals(status) && prompt.isBlank()) {
            throw invalid("未确认的 decision 必须提供 prompt");
        }
        if (STATUS_CONFIRMED.equals(status) && candidateSummary.isBlank()) {
            throw invalid("已确认的 decision 必须提供 candidateSummary");
        }
        List<Long> sourceMessageIds = normalizeSourceMessageIds(decision.sourceMessageIds());
        return new Decision(
                key,
                title,
                status,
                decision.required(),
                prompt,
                candidateSummary,
                sourceMessageIds);
    }

    /**
     * 规范化消息来源 ID。
     *
     * @param sourceMessageIds 原始消息来源 ID
     * @return 去重后的消息来源 ID
     */
    private List<Long> normalizeSourceMessageIds(List<Long> sourceMessageIds) {
        if (sourceMessageIds == null || sourceMessageIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long sourceMessageId : sourceMessageIds) {
            if (sourceMessageId == null || sourceMessageId <= 0) {
                throw invalid("sourceMessageIds 必须为正整数");
            }
            normalized.add(sourceMessageId);
        }
        if (normalized.size() > SOURCE_MAX_COUNT_PER_DECISION) {
            throw invalid("单个 decision 的 sourceMessageIds 不能超过 "
                    + SOURCE_MAX_COUNT_PER_DECISION + " 项");
        }
        return List.copyOf(normalized);
    }

    /**
     * 清理并检查文本长度。
     *
     * @param value 原始文本
     * @param maxLength 最大长度
     * @param fieldName 字段名
     * @return 清理后的文本
     */
    private String normalizeText(String value, int maxLength, String fieldName) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw invalid(fieldName + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    /**
     * 创建共识契约错误。
     *
     * @param message 错误信息
     * @return 业务异常
     */
    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.CHAPTER_CONSENSUS_INVALID, message);
    }

    /**
     * 创建 Brief 确认阻断错误。
     *
     * @param message 错误信息
     * @return 业务异常
     */
    private BusinessException confirmationBlocked(String message) {
        return new BusinessException(ErrorCode.CHAPTER_BRIEF_CONFIRMATION_BLOCKED, message);
    }
}
