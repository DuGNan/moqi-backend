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
        validateSourceMessages(chapterId, conversationId, sourceIds);

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
        List<SourceMessagePreview> previews = sourceIds.stream()
                .map(messages::get)
                .map(this::sourcePreview)
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

    /**
     * 批量校验来源消息确实属于当前章节。
     *
     * @param chapterId 章节 ID
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
    private SourceMessagePreview sourcePreview(ChapterConversationMessageEntity message) {
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
                message.getGmtCreate());
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
