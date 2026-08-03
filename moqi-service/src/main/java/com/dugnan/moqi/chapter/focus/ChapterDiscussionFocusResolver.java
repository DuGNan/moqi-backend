package com.dugnan.moqi.chapter.focus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusCodec;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.Decision;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusDocument;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.focus.ResolvedDiscussionFocus.DiscussionFocusSource;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 校验讨论对焦引用并从服务端持久化数据解析受控上下文。
 */
@Service
public class ChapterDiscussionFocusResolver {

    private static final String BRIEF_STATUS_DRAFT = "draft";
    private static final String DECISION_STATUS_CONFIRMED = "confirmed";

    private final ChapterBriefMapper briefMapper;

    private final ChapterConversationMessageMapper messageMapper;

    private final ChapterConsensusCodec codec;

    /**
     * 创建讨论对焦解析器。
     *
     * @param briefMapper Brief 数据访问对象
     * @param messageMapper 消息数据访问对象
     * @param codec 共识编解码器
     */
    public ChapterDiscussionFocusResolver(
            ChapterBriefMapper briefMapper,
            ChapterConversationMessageMapper messageMapper,
            ChapterConsensusCodec codec) {
        this.briefMapper = briefMapper;
        this.messageMapper = messageMapper;
        this.codec = codec;
    }

    /**
     * 解析当前章节会话中的待决对焦。
     *
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @param briefId Brief ID
     * @param decisionKey 待决键
     * @return 受控对焦上下文
     */
    public ResolvedDiscussionFocus resolve(
            Long chapterId,
            Long conversationId,
            Long briefId,
            String decisionKey) {
        if (chapterId == null
                || conversationId == null
                || briefId == null
                || !StringUtils.hasText(decisionKey)) {
            throw invalid("discussionFocus 必须同时提供 briefId 和 decisionKey");
        }
        ChapterBriefEntity brief = briefMapper.findByIdAndChapterId(briefId, chapterId);
        if (brief == null) {
            throw invalid("discussionFocus 引用的 Brief 不存在或不属于当前章节");
        }
        ChapterBriefEntity latestDraft =
                briefMapper.findLatestByChapterIdAndStatus(chapterId, BRIEF_STATUS_DRAFT);
        ChapterBriefEntity latestConfirmed =
                briefMapper.findLatestByChapterIdAndStatus(chapterId, "confirmed");
        if (!isCurrentDraft(brief, latestDraft, latestConfirmed)) {
            throw stale();
        }
        ChapterConsensusDocument document = codec.read(brief.getBriefContent());
        if (document.consensus() == null) {
            throw invalid("历史文本 Brief 不能作为待决对焦来源");
        }
        String normalizedKey = decisionKey.trim();
        Decision decision = document.consensus().decisions().stream()
                .filter(item -> item.key().equals(normalizedKey))
                .findFirst()
                .orElseThrow(() -> invalid("Brief 中不存在指定 decisionKey"));
        if (DECISION_STATUS_CONFIRMED.equals(decision.status())) {
            throw stale();
        }

        List<DiscussionFocusSource> sources =
                resolveSources(chapterId, conversationId, decision.sourceMessageIds());
        return new ResolvedDiscussionFocus(
                brief.getId(),
                brief.getVersion(),
                decision.key(),
                decision.title(),
                decision.prompt(),
                decision.candidateSummary(),
                brief.getBriefContent(),
                sources);
    }

    private boolean isCurrentDraft(
            ChapterBriefEntity brief,
            ChapterBriefEntity latestDraft,
            ChapterBriefEntity latestConfirmed) {
        boolean isDraft = BRIEF_STATUS_DRAFT.equals(brief.getBriefStatus());
        boolean hasNewerConfirmedBrief = latestConfirmed != null
                && latestDraft != null
                && latestDraft.getId() < latestConfirmed.getId();
        return isDraft
                && latestDraft != null
                && !hasNewerConfirmedBrief
                && brief.getId().equals(latestDraft.getId());
    }

    /**
     * 一次批量查询并按原引用顺序转换来源消息。
     *
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @param sourceIds 来源消息 ID
     * @return 来源消息
     */
    private List<DiscussionFocusSource> resolveSources(
            Long chapterId,
            Long conversationId,
            List<Long> sourceIds) {
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        List<ChapterConversationMessageEntity> messages = messageMapper.selectBatchIds(sourceIds);
        Map<Long, ChapterConversationMessageEntity> byId = new LinkedHashMap<>();
        for (ChapterConversationMessageEntity message : messages) {
            if (message != null
                    && chapterId.equals(message.getChapterId())
                    && conversationId.equals(message.getConversationId())
                    && !Integer.valueOf(1).equals(message.getDeleted())) {
                byId.put(message.getId(), message);
            }
        }
        if (byId.size() != sourceIds.size() || !byId.keySet().containsAll(sourceIds)) {
            throw invalid("待决来源消息不属于当前章节会话");
        }
        return sourceIds.stream()
                .map(byId::get)
                .map(message -> new DiscussionFocusSource(
                        message.getId(),
                        message.getMessageRole(),
                        message.getContent()))
                .toList();
    }

    /**
     * 创建非法对焦错误。
     *
     * @param message 错误信息
     * @return 业务异常
     */
    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.DISCUSSION_FOCUS_INVALID, message);
    }

    /**
     * 创建过期对焦错误。
     *
     * @return 业务异常
     */
    private BusinessException stale() {
        return new BusinessException(
                ErrorCode.DISCUSSION_FOCUS_STALE,
                "待决已确认或引用的 Brief 不再是最新草稿，请刷新后重试");
    }
}
