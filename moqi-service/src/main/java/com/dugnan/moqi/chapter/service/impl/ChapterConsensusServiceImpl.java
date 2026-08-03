package com.dugnan.moqi.chapter.service.impl;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusCodec;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.Decision;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusDocument;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusValidator;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.BriefState;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.BriefView;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConfirmBriefRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.CreateBriefDraftRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.DecisionSources;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ResolveDecisionRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.SourceMessagePreview;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.service.ChapterConsensusService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 实现章节结构化共识的版本追加、确认和来源追溯。
 */
@Service
public class ChapterConsensusServiceImpl implements ChapterConsensusService {

    private static final String STATUS_DRAFT = "draft";

    private static final String STATUS_CONFIRMED = "confirmed";

    private static final int SOURCE_PREVIEW_MAX_LENGTH = 160;

    private final WorkMapper workMapper;

    private final ChapterMapper chapterMapper;

    private final ChapterBriefMapper briefMapper;

    private final ChapterConversationMessageMapper messageMapper;

    private final ChapterConsensusCodec codec;

    private final ChapterConsensusValidator validator;

    /**
     * 创建章节共识服务。
     *
     * @param workMapper 作品数据访问对象
     * @param chapterMapper 章节数据访问对象
     * @param briefMapper Brief 数据访问对象
     * @param messageMapper 会话消息数据访问对象
     * @param codec 共识编解码器
     * @param validator 共识校验器
     */
    public ChapterConsensusServiceImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterBriefMapper briefMapper,
            ChapterConversationMessageMapper messageMapper,
            ChapterConsensusCodec codec,
            ChapterConsensusValidator validator) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.briefMapper = briefMapper;
        this.messageMapper = messageMapper;
        this.codec = codec;
        this.validator = validator;
    }

    @Override
    public BriefState getState(Long chapterId) {
        requireChapterAndWork(chapterId);
        ChapterBriefEntity latestDraft =
                briefMapper.findLatestByChapterIdAndStatus(chapterId, STATUS_DRAFT);
        ChapterBriefEntity latestConfirmed =
                briefMapper.findLatestByChapterIdAndStatus(chapterId, STATUS_CONFIRMED);
        if (!isNewerDraft(latestDraft, latestConfirmed)) {
            latestDraft = null;
        }
        return new BriefState(viewOrNull(latestDraft), viewOrNull(latestConfirmed));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public BriefView createDraft(Long chapterId, CreateBriefDraftRequest request) {
        ChapterEntity chapter = requireChapterAndWork(chapterId);
        if (request != null && request.baseBriefId() != null) {
            requireBrief(chapterId, request.baseBriefId());
        }
        ChapterConsensusContentV1 normalized =
                validator.normalizeDraft(request == null ? null : request.consensus());
        Set<Long> sourceIds = sourceMessageIds(normalized);
        Long conversationId = request == null ? null : request.conversationId();
        if (!sourceIds.isEmpty() && conversationId == null) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_CONSENSUS_INVALID,
                    "引用来源消息时必须提供 conversationId");
        }
        Map<Long, ChapterConversationMessageEntity> sourceMessages =
                validateSourceMessages(chapterId, conversationId, sourceIds);
        validateSourceQuotes(normalized, sourceMessages);

        ChapterBriefEntity brief = new ChapterBriefEntity();
        brief.setWorkId(chapter.getWorkId());
        brief.setChapterId(chapterId);
        brief.setBriefStatus(STATUS_DRAFT);
        brief.setBriefContent(codec.write(normalized));
        brief.setDeleted(0);
        brief.setVersion(0);
        briefMapper.insert(brief);
        return view(brief);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public BriefView confirm(Long chapterId, Long briefId, ConfirmBriefRequest request) {
        requireChapterAndWork(chapterId);
        Integer expectedVersion = request == null ? null : request.baseVersion();
        if (expectedVersion == null || expectedVersion < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "expectedVersion 必须为非负整数");
        }

        ChapterBriefEntity brief = requireBrief(chapterId, briefId);
        if (STATUS_CONFIRMED.equals(brief.getBriefStatus())
                && Integer.valueOf(expectedVersion + 1).equals(brief.getVersion())) {
            return view(brief);
        }
        if (!STATUS_DRAFT.equals(brief.getBriefStatus())) {
            throw versionConflict();
        }

        ChapterConsensusDocument document = codec.read(brief.getBriefContent());
        if (document.consensus() == null) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_BRIEF_CONFIRMATION_BLOCKED,
                    "历史文本 Brief 不能直接确认为结构化共识");
        }
        validator.requireConfirmable(document.consensus());

        int changed = briefMapper.confirmDraft(briefId, chapterId, expectedVersion);
        if (changed != 1) {
            ChapterBriefEntity current = briefMapper.findByIdAndChapterId(briefId, chapterId);
            if (current != null
                    && STATUS_CONFIRMED.equals(current.getBriefStatus())
                    && Integer.valueOf(expectedVersion + 1).equals(current.getVersion())) {
                return view(current);
            }
            throw versionConflict();
        }
        return view(requireBrief(chapterId, briefId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public BriefView resolveDecision(
            Long chapterId,
            Long briefId,
            String decisionKey,
            ResolveDecisionRequest request) {
        requireChapterAndWork(chapterId);
        if (request == null || request.baseVersion() == null || request.baseVersion() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "baseVersion 必须为非负整数");
        }
        String action = request.action() == null ? "" : request.action().trim();
        String status = decisionStatus(action);
        String normalizedKey = StringUtils.hasText(decisionKey) ? decisionKey.trim() : "";
        ChapterBriefEntity brief = requireBrief(chapterId, briefId);
        ChapterBriefEntity latest = currentDraft(chapterId);
        if (latest == null || !latest.getId().equals(briefId)) {
            if (latest != null && decisionHasStatus(latest, normalizedKey, status)) {
                return view(latest);
            }
            throw versionConflict();
        }
        if (!request.baseVersion().equals(brief.getVersion())) {
            throw versionConflict();
        }
        ChapterConsensusDocument document = codec.read(brief.getBriefContent());
        if (document.consensus() == null) {
            throw new BusinessException(ErrorCode.CHAPTER_CONSENSUS_INVALID, "历史文本 Brief 没有可处理的待决项");
        }
        Decision currentDecision = document.consensus().decisions().stream()
                .filter(item -> item.key().equals(normalizedKey))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CHAPTER_CONSENSUS_INVALID, "Brief 中不存在指定 decisionKey"));
        if (currentDecision.status().equals(status)) {
            return view(brief);
        }
        List<Decision> decisions = document.consensus().decisions().stream()
                .map(decision -> resolveDecision(decision, normalizedKey, status))
                .toList();
        ChapterConsensusContentV1 updated = new ChapterConsensusContentV1(
                document.consensus().schemaVersion(), document.consensus().chapterTask(),
                document.consensus().stateChange(), document.consensus().keyPush(),
                document.consensus().readerProgress(), document.consensus().writingBoundaries(), decisions);
        ChapterBriefEntity appended = new ChapterBriefEntity();
        appended.setWorkId(brief.getWorkId());
        appended.setChapterId(chapterId);
        appended.setBriefStatus(STATUS_DRAFT);
        appended.setBriefContent(codec.write(validator.normalizeDraft(updated)));
        appended.setDeleted(0);
        appended.setVersion(0);
        briefMapper.insert(appended);
        return view(appended);
    }

    @Override
    public DecisionSources getDecisionSources(Long chapterId, Long briefId, String decisionKey) {
        requireChapterAndWork(chapterId);
        String normalizedKey = StringUtils.hasText(decisionKey) ? decisionKey.trim() : "";
        ChapterBriefEntity brief = requireBrief(chapterId, briefId);
        ChapterConsensusDocument document = codec.read(brief.getBriefContent());
        if (document.consensus() == null) {
            throw new BusinessException(ErrorCode.CHAPTER_CONSENSUS_INVALID, "历史文本 Brief 没有待决来源");
        }
        Decision decision = document.consensus().decisions().stream()
                .filter(item -> item.key().equals(normalizedKey))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CHAPTER_CONSENSUS_INVALID,
                        "Brief 中不存在指定 decisionKey"));
        List<Long> sourceIds = decision.sourceMessageIds();
        Map<Long, ChapterConversationMessageEntity> messages =
                validateSourceMessages(chapterId, null, new LinkedHashSet<>(sourceIds));
        Map<Long, List<String>> quotes = decision.sourceQuotes().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ChapterConsensusContentV1.SourceQuote::messageId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(
                                ChapterConsensusContentV1.SourceQuote::quote,
                                java.util.stream.Collectors.toList())));
        List<SourceMessagePreview> previews = sourceIds.stream()
                .map(id -> sourcePreview(messages.get(id), quotes.getOrDefault(id, List.of())))
                .toList();
        return new DecisionSources(briefId, normalizedKey, previews);
    }

    /**
     * 查询章节并验证作品仍存在。
     *
     * @param chapterId 章节 ID
     * @return 章节实体
     */
    private ChapterEntity requireChapterAndWork(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        WorkEntity work = chapter.getWorkId() == null ? null : workMapper.selectById(chapter.getWorkId());
        if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return chapter;
    }

    /**
     * 在章节范围内查询 Brief。
     *
     * @param chapterId 章节 ID
     * @param briefId Brief ID
     * @return Brief 实体
     */
    private ChapterBriefEntity requireBrief(Long chapterId, Long briefId) {
        ChapterBriefEntity brief =
                briefId == null ? null : briefMapper.findByIdAndChapterId(briefId, chapterId);
        if (brief == null) {
            throw new BusinessException(ErrorCode.CHAPTER_BRIEF_NOT_FOUND, "章节 Brief 不存在");
        }
        return brief;
    }

    /**
     * 获取仍可继续处理的草稿；已确认版本之后不存在新草稿时，历史草稿不再是当前态。
     *
     * @param chapterId 章节 ID
     * @return 当前草稿，不存在时返回 {@code null}
     */
    private ChapterBriefEntity currentDraft(Long chapterId) {
        ChapterBriefEntity latestDraft = briefMapper.findLatestByChapterIdAndStatus(chapterId, STATUS_DRAFT);
        ChapterBriefEntity latestConfirmed = briefMapper.findLatestByChapterIdAndStatus(chapterId, STATUS_CONFIRMED);
        return isNewerDraft(latestDraft, latestConfirmed) ? latestDraft : null;
    }

    /**
     * 判断草稿是否在已确认版本之后创建。
     *
     * @param draft 最近草稿
     * @param confirmed 最近确认版本
     * @return 草稿可作为当前态时返回 {@code true}
     */
    private boolean isNewerDraft(ChapterBriefEntity draft, ChapterBriefEntity confirmed) {
        return draft != null && (confirmed == null || draft.getId() > confirmed.getId());
    }

    /**
     * 收集共识引用的全部来源消息 ID。
     *
     * @param content 结构化共识
     * @return 去重且保序的消息 ID
     */
    private Set<Long> sourceMessageIds(ChapterConsensusContentV1 content) {
        Set<Long> sourceIds = new LinkedHashSet<>();
        for (Decision decision : content.decisions()) {
            sourceIds.addAll(decision.sourceMessageIds());
        }
        return sourceIds;
    }

    private void validateSourceQuotes(
            ChapterConsensusContentV1 content,
            Map<Long, ChapterConversationMessageEntity> messages) {
        for (Decision decision : content.decisions()) {
            for (ChapterConsensusContentV1.SourceQuote sourceQuote : decision.sourceQuotes()) {
                ChapterConversationMessageEntity message = messages.get(sourceQuote.messageId());
                if (message == null || !normalizeForQuote(message.getContent()).contains(normalizeForQuote(sourceQuote.quote()))) {
                    throw new BusinessException(ErrorCode.CHAPTER_CONSENSUS_INVALID, "sourceQuotes 未命中来源消息原文");
                }
            }
        }
    }

    private String normalizeForQuote(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private String decisionStatus(String action) {
        return switch (action) {
            case "adopt" -> STATUS_CONFIRMED;
            case "reject" -> "rejected";
            case "discuss" -> "discussing";
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "action 仅支持 adopt、reject 或 discuss");
        };
    }

    private boolean decisionHasStatus(ChapterBriefEntity brief, String decisionKey, String status) {
        ChapterConsensusDocument document = codec.read(brief.getBriefContent());
        return document.consensus() != null && document.consensus().decisions().stream()
                .anyMatch(item -> item.key().equals(decisionKey) && item.status().equals(status));
    }

    private Decision resolveDecision(Decision decision, String decisionKey, String status) {
        if (!decision.key().equals(decisionKey)) {
            return decision;
        }
        if (STATUS_CONFIRMED.equals(status) && decision.candidateSummary().isBlank()) {
            throw new BusinessException(ErrorCode.CHAPTER_CONSENSUS_INVALID, "采用候选前必须存在候选摘要");
        }
        return new Decision(decision.key(), decision.title(), status, decision.required(), decision.prompt(),
                decision.candidateSummary(), decision.sourceMessageIds(), decision.sourceQuotes());
    }

    /**
     * 批量校验来源消息确实属于当前章节。
     *
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @param sourceIds 来源消息 ID
     * @return 按消息 ID 索引的实体
     */
    private Map<Long, ChapterConversationMessageEntity> validateSourceMessages(
            Long chapterId,
            Long conversationId,
            Set<Long> sourceIds) {
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        List<ChapterConversationMessageEntity> messages = messageMapper.selectBatchIds(sourceIds);
        Map<Long, ChapterConversationMessageEntity> validMessages = new LinkedHashMap<>();
        Set<Long> conversationIds = new LinkedHashSet<>();
        for (ChapterConversationMessageEntity message : messages) {
            if (message == null || !chapterId.equals(message.getChapterId())) {
                continue;
            }
            if (conversationId != null && !conversationId.equals(message.getConversationId())) {
                continue;
            }
            if (Integer.valueOf(1).equals(message.getDeleted())) {
                continue;
            }
            validMessages.put(message.getId(), message);
            conversationIds.add(message.getConversationId());
        }
        if (!validMessages.keySet().equals(sourceIds) || conversationIds.size() != 1) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_CONSENSUS_INVALID,
                    "sourceMessageIds 包含不存在、已删除或不属于当前章节的消息");
        }
        return validMessages;
    }

    /**
     * 将 Brief 转换为兼容视图。
     *
     * @param brief Brief 实体
     * @return Brief 视图
     */
    private BriefView view(ChapterBriefEntity brief) {
        ChapterConsensusDocument document = codec.read(brief.getBriefContent());
        return new BriefView(
                brief.getId(),
                brief.getWorkId(),
                brief.getChapterId(),
                brief.getBriefStatus(),
                brief.getVersion(),
                document.contentFormat(),
                document.consensus(),
                document.legacyText(),
                brief.getGmtCreate(),
                brief.getGmtModified());
    }

    /**
     * 将可空 Brief 转换为兼容视图。
     *
     * @param brief Brief 实体
     * @return Brief 视图或 null
     */
    private BriefView viewOrNull(ChapterBriefEntity brief) {
        return brief == null ? null : view(brief);
    }

    /**
     * 将来源消息转换为有限长度预览。
     *
     * @param message 消息实体
     * @return 消息预览
     */
    private SourceMessagePreview sourcePreview(ChapterConversationMessageEntity message, List<String> quotes) {
        String content = message.getContent() == null
                ? ""
                : message.getContent().trim().replaceAll("\\s+", " ");
        String preview = content.length() <= SOURCE_PREVIEW_MAX_LENGTH
                ? content
                : content.substring(0, SOURCE_PREVIEW_MAX_LENGTH);
        return new SourceMessagePreview(
                message.getId(),
                message.getConversationId(),
                message.getMessageRole(),
                preview,
                message.getGmtCreate(),
                quotes);
    }

    /**
     * 创建 Brief 版本冲突异常。
     *
     * @return 版本冲突异常
     */
    private BusinessException versionConflict() {
        return new BusinessException(
                ErrorCode.CHAPTER_BRIEF_VERSION_CONFLICT,
                "Brief 已被更新或确认，请刷新后重试");
    }
}
