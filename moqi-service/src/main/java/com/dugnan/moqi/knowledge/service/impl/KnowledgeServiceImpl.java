package com.dugnan.moqi.knowledge.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ChapterKeyEventDetail;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ChapterKeyEventList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ChapterSummaryDetail;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ConfirmSettingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ConfirmSettingResult;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.CreateForeshadowingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ForeshadowingDetail;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ForeshadowingList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.IgnoreSettingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.IgnoreSettingResult;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.SettingCandidateDetail;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.SettingCandidateList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.SettingDetail;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.SettingList;
import com.dugnan.moqi.knowledge.entity.ChapterKeyEventEntity;
import com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity;
import com.dugnan.moqi.knowledge.entity.ForeshadowingItemEntity;
import com.dugnan.moqi.knowledge.entity.SettingCandidateEntity;
import com.dugnan.moqi.knowledge.entity.SettingEntryEntity;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.SettingCandidateMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.knowledge.service.KnowledgeService;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 实现第一版设定、伏笔、章节摘要和关键事件业务流程。
 */
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_IGNORED = "ignored";
    private static final String STATUS_ACTIVE = "active";
    private static final Set<String> CANDIDATE_STATUSES =
            Set.of(STATUS_PENDING, STATUS_CONFIRMED, STATUS_IGNORED);
    private static final Set<String> SETTING_TYPES =
            Set.of("character", "place", "organization", "rule", "item", "other");
    private static final Set<String> ENTRY_STATUSES = Set.of(STATUS_ACTIVE, "deprecated");
    private static final Set<String> FORESHADOWING_STATUSES =
            Set.of("planted", "pending_payoff", "paid_off", "abandoned");

    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final SettingCandidateMapper candidateMapper;
    private final SettingEntryMapper settingMapper;
    private final ForeshadowingItemMapper foreshadowingMapper;
    private final ChapterSummaryMapper summaryMapper;
    private final ChapterKeyEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建知识层服务。
     *
     * @param workMapper 作品数据访问对象
     * @param chapterMapper 章节数据访问对象
     * @param candidateMapper 设定候选数据访问对象
     * @param settingMapper 正式设定数据访问对象
     * @param foreshadowingMapper 伏笔数据访问对象
     * @param summaryMapper 章节摘要数据访问对象
     * @param eventMapper 章节关键事件数据访问对象
     * @param objectMapper JSON 解析器
     */
    public KnowledgeServiceImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            SettingCandidateMapper candidateMapper,
            SettingEntryMapper settingMapper,
            ForeshadowingItemMapper foreshadowingMapper,
            ChapterSummaryMapper summaryMapper,
            ChapterKeyEventMapper eventMapper,
            ObjectMapper objectMapper) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.candidateMapper = candidateMapper;
        this.settingMapper = settingMapper;
        this.foreshadowingMapper = foreshadowingMapper;
        this.summaryMapper = summaryMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public SettingCandidateList listSettingCandidates(
            Long workId,
            Long chapterId,
            String candidateStatus,
            String settingType,
            String keyword) {
        requireWork(workId);
        String status = StringUtils.hasText(candidateStatus) ? candidateStatus.trim() : STATUS_PENDING;
        validateValue(status, CANDIDATE_STATUSES, "candidateStatus");
        validateOptional(settingType, SETTING_TYPES, "settingType");

        LambdaQueryWrapper<SettingCandidateEntity> query = new LambdaQueryWrapper<SettingCandidateEntity>()
                .eq(SettingCandidateEntity::getWorkId, workId)
                .eq(chapterId != null, SettingCandidateEntity::getChapterId, chapterId)
                .eq(SettingCandidateEntity::getCandidateStatus, status)
                .eq(StringUtils.hasText(settingType), SettingCandidateEntity::getSettingType, trim(settingType))
                .eq(SettingCandidateEntity::getDeleted, 0)
                .and(StringUtils.hasText(keyword), wrapper -> wrapper
                        .like(SettingCandidateEntity::getName, trim(keyword))
                        .or()
                        .like(SettingCandidateEntity::getContent, trim(keyword)))
                .orderByDesc(SettingCandidateEntity::getGmtModified)
                .orderByDesc(SettingCandidateEntity::getId);
        List<SettingCandidateDetail> candidates = candidateMapper.selectList(query).stream()
                .map(this::candidateDetail)
                .toList();
        return new SettingCandidateList(workId, candidates);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ConfirmSettingResult confirmSettingCandidate(Long candidateId, ConfirmSettingRequest request) {
        SettingCandidateEntity candidate = requireCandidate(candidateId);
        if (STATUS_CONFIRMED.equals(candidate.getCandidateStatus())) {
            return new ConfirmSettingResult(
                    candidate.getId(),
                    STATUS_CONFIRMED,
                    candidate.getConfirmedSettingId(),
                    candidate.getGmtModified());
        }
        if (!STATUS_PENDING.equals(candidate.getCandidateStatus())) {
            throw badRequest("当前候选状态不允许确认");
        }

        String settingType = requiredSettingType(request == null ? null : request.settingType());
        String name = requiredText(request == null ? null : request.name(), "设定名称不能为空");
        String content = requiredText(request == null ? null : request.content(), "设定内容不能为空");
        Long mergeToSettingId = request == null ? null : request.mergeToSettingId();
        SettingEntryEntity setting = mergeToSettingId == null
                ? newSetting(candidate)
                : requireSetting(mergeToSettingId, candidate.getWorkId());
        setting.setSettingType(settingType);
        setting.setName(name);
        setting.setContent(content);
        setting.setEntryStatus(STATUS_ACTIVE);
        if (setting.getId() == null) {
            settingMapper.insert(setting);
        } else {
            settingMapper.updateById(setting);
        }

        candidate.setCandidateStatus(STATUS_CONFIRMED);
        candidate.setConfirmedSettingId(setting.getId());
        candidate.setGmtModified(LocalDateTime.now());
        candidateMapper.updateById(candidate);
        return new ConfirmSettingResult(
                candidate.getId(),
                candidate.getCandidateStatus(),
                setting.getId(),
                candidate.getGmtModified());
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public IgnoreSettingResult ignoreSettingCandidate(Long candidateId, IgnoreSettingRequest request) {
        SettingCandidateEntity candidate = requireCandidate(candidateId);
        if (STATUS_IGNORED.equals(candidate.getCandidateStatus())) {
            return ignoreResult(candidate);
        }
        if (!STATUS_PENDING.equals(candidate.getCandidateStatus())) {
            throw badRequest("当前候选状态不允许忽略");
        }
        candidate.setCandidateStatus(STATUS_IGNORED);
        candidate.setGmtModified(LocalDateTime.now());
        candidateMapper.updateById(candidate);
        return ignoreResult(candidate);
    }

    @Override
    public SettingList listSettings(Long workId, String settingType, String entryStatus, String keyword) {
        requireWork(workId);
        validateOptional(settingType, SETTING_TYPES, "settingType");
        String status = StringUtils.hasText(entryStatus) ? entryStatus.trim() : STATUS_ACTIVE;
        validateValue(status, ENTRY_STATUSES, "entryStatus");

        LambdaQueryWrapper<SettingEntryEntity> query = new LambdaQueryWrapper<SettingEntryEntity>()
                .eq(SettingEntryEntity::getWorkId, workId)
                .eq(StringUtils.hasText(settingType), SettingEntryEntity::getSettingType, trim(settingType))
                .eq(SettingEntryEntity::getEntryStatus, status)
                .eq(SettingEntryEntity::getDeleted, 0)
                .and(StringUtils.hasText(keyword), wrapper -> wrapper
                        .like(SettingEntryEntity::getName, trim(keyword))
                        .or()
                        .like(SettingEntryEntity::getAliasesJson, trim(keyword))
                        .or()
                        .like(SettingEntryEntity::getContent, trim(keyword)))
                .orderByDesc(SettingEntryEntity::getGmtModified)
                .orderByDesc(SettingEntryEntity::getId);
        List<SettingDetail> settings = settingMapper.selectList(query).stream()
                .map(this::settingDetail)
                .toList();
        return new SettingList(workId, settings);
    }

    @Override
    public ForeshadowingList listForeshadowings(
            Long workId,
            String status,
            Long sourceChapterId,
            Long payoffChapterId) {
        requireWork(workId);
        validateOptional(status, FORESHADOWING_STATUSES, "status");
        LambdaQueryWrapper<ForeshadowingItemEntity> query =
                new LambdaQueryWrapper<ForeshadowingItemEntity>()
                        .eq(ForeshadowingItemEntity::getWorkId, workId)
                        .eq(StringUtils.hasText(status), ForeshadowingItemEntity::getStatus, trim(status))
                        .eq(sourceChapterId != null,
                                ForeshadowingItemEntity::getSourceChapterId,
                                sourceChapterId)
                        .eq(ForeshadowingItemEntity::getDeleted, 0)
                        .and(payoffChapterId != null, wrapper -> wrapper
                                .eq(ForeshadowingItemEntity::getExpectedPayoffChapterId, payoffChapterId)
                                .or()
                                .eq(ForeshadowingItemEntity::getActualPayoffChapterId, payoffChapterId))
                        .orderByDesc(ForeshadowingItemEntity::getGmtModified)
                        .orderByDesc(ForeshadowingItemEntity::getId);
        List<ForeshadowingDetail> foreshadowings = foreshadowingMapper.selectList(query).stream()
                .map(this::foreshadowingDetail)
                .toList();
        return new ForeshadowingList(workId, foreshadowings);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ForeshadowingDetail createForeshadowing(Long workId, CreateForeshadowingRequest request) {
        requireWork(workId);
        if (request == null || request.sourceChapterId() == null) {
            throw badRequest("sourceChapterId 不能为空");
        }
        requireChapterInWork(request.sourceChapterId(), workId, "来源章节不属于当前作品");
        if (request.expectedPayoffChapterId() != null) {
            requireChapterInWork(request.expectedPayoffChapterId(), workId, "预计回收章节不属于当前作品");
        }
        validateOffsets(request.sourceText(), request.sourceStartOffset(), request.sourceEndOffset());
        String status = StringUtils.hasText(request.status()) ? request.status().trim() : "planted";
        validateValue(status, FORESHADOWING_STATUSES, "status");

        ForeshadowingItemEntity entity = new ForeshadowingItemEntity();
        entity.setWorkId(workId);
        entity.setSourceChapterId(request.sourceChapterId());
        entity.setTitle(requiredText(request.title(), "伏笔标题不能为空"));
        entity.setDescription(requiredText(request.description(), "伏笔说明不能为空"));
        entity.setSourceText(request.sourceText());
        entity.setSourceStartOffset(request.sourceStartOffset());
        entity.setSourceEndOffset(request.sourceEndOffset());
        entity.setStatus(status);
        entity.setExpectedPayoffChapterId(request.expectedPayoffChapterId());
        entity.setDeleted(0);
        entity.setVersion(0);
        LocalDateTime createdAt = LocalDateTime.now();
        entity.setGmtCreate(createdAt);
        entity.setGmtModified(createdAt);
        foreshadowingMapper.insert(entity);
        return foreshadowingDetail(entity);
    }

    @Override
    public ChapterSummaryDetail getChapterSummary(Long chapterId) {
        ChapterEntity chapter = requireChapter(chapterId);
        requireWork(chapter.getWorkId());
        return summaryMapper.selectList(
                        new LambdaQueryWrapper<ChapterSummaryEntity>()
                                .eq(ChapterSummaryEntity::getChapterId, chapterId)
                                .eq(ChapterSummaryEntity::getDeleted, 0)
                                .orderByDesc(ChapterSummaryEntity::getGmtModified)
                                .orderByDesc(ChapterSummaryEntity::getId))
                .stream()
                .findFirst()
                .map(this::summaryDetail)
                .orElse(null);
    }

    @Override
    public ChapterKeyEventList listChapterKeyEvents(Long chapterId) {
        ChapterEntity chapter = requireChapter(chapterId);
        requireWork(chapter.getWorkId());
        List<ChapterKeyEventDetail> events = eventMapper.selectList(
                        new LambdaQueryWrapper<ChapterKeyEventEntity>()
                                .eq(ChapterKeyEventEntity::getChapterId, chapterId)
                                .eq(ChapterKeyEventEntity::getDeleted, 0)
                                .orderByAsc(ChapterKeyEventEntity::getOccurredOrder)
                                .orderByAsc(ChapterKeyEventEntity::getId))
                .stream()
                .map(this::eventDetail)
                .toList();
        return new ChapterKeyEventList(chapter.getWorkId(), chapterId, events);
    }

    /**
     * 创建正式设定初始实体。
     *
     * @param candidate 来源候选
     * @return 正式设定实体
     */
    private SettingEntryEntity newSetting(SettingCandidateEntity candidate) {
        SettingEntryEntity setting = new SettingEntryEntity();
        setting.setWorkId(candidate.getWorkId());
        setting.setAliasesJson("[]");
        setting.setAttributesJson("{}");
        setting.setSourceChapterId(candidate.getChapterId());
        setting.setSourceCandidateId(candidate.getId());
        setting.setDeleted(0);
        setting.setVersion(0);
        return setting;
    }

    /**
     * 获取未删除作品。
     *
     * @param id 作品 ID
     * @return 作品实体
     */
    private WorkEntity requireWork(Long id) {
        WorkEntity entity = id == null ? null : workMapper.selectById(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return entity;
    }

    /**
     * 获取未删除章节。
     *
     * @param id 章节 ID
     * @return 章节实体
     */
    private ChapterEntity requireChapter(Long id) {
        ChapterEntity entity = id == null ? null : chapterMapper.selectById(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return entity;
    }

    /**
     * 校验章节归属。
     *
     * @param chapterId 章节 ID
     * @param workId 作品 ID
     * @param message 归属错误消息
     * @return 章节实体
     */
    private ChapterEntity requireChapterInWork(Long chapterId, Long workId, String message) {
        ChapterEntity chapter = requireChapter(chapterId);
        if (!workId.equals(chapter.getWorkId())) {
            throw badRequest(message);
        }
        return chapter;
    }

    /**
     * 获取未删除候选。
     *
     * @param id 候选 ID
     * @return 候选实体
     */
    private SettingCandidateEntity requireCandidate(Long id) {
        SettingCandidateEntity entity = id == null ? null : candidateMapper.selectById(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.SETTING_CANDIDATE_NOT_FOUND, "设定候选不存在");
        }
        return entity;
    }

    /**
     * 获取同作品未删除正式设定。
     *
     * @param id 设定 ID
     * @param workId 作品 ID
     * @return 正式设定实体
     */
    private SettingEntryEntity requireSetting(Long id, Long workId) {
        SettingEntryEntity entity = id == null ? null : settingMapper.selectById(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.SETTING_NOT_FOUND, "正式设定不存在");
        }
        if (!workId.equals(entity.getWorkId())) {
            throw badRequest("不能合并到其他作品的设定");
        }
        return entity;
    }

    /**
     * 校验伏笔偏移范围。
     *
     * @param sourceText 来源原文
     * @param startOffset 起始偏移
     * @param endOffset 结束偏移
     */
    private void validateOffsets(String sourceText, Integer startOffset, Integer endOffset) {
        if (startOffset == null && endOffset == null) {
            return;
        }
        if (startOffset == null || endOffset == null || !StringUtils.hasText(sourceText)) {
            throw badRequest("伏笔偏移必须与来源原文一起完整提供");
        }
        if (startOffset < 0 || endOffset < startOffset || endOffset > sourceText.length()) {
            throw badRequest("伏笔偏移范围非法");
        }
    }

    /**
     * 校验设定类型。
     *
     * @param value 原始类型
     * @return 合法类型
     */
    private String requiredSettingType(String value) {
        String settingType = requiredText(value, "settingType 不能为空");
        validateValue(settingType, SETTING_TYPES, "settingType");
        return settingType;
    }

    /**
     * 校验必填文本。
     *
     * @param value 原始文本
     * @param message 错误消息
     * @return 规范化文本
     */
    private String requiredText(String value, String message) {
        String text = trim(value);
        if (!StringUtils.hasText(text)) {
            throw badRequest(message);
        }
        return text;
    }

    /**
     * 校验可选枚举。
     *
     * @param value 原始值
     * @param allowed 允许值
     * @param field 字段名
     */
    private void validateOptional(String value, Set<String> allowed, String field) {
        if (StringUtils.hasText(value)) {
            validateValue(value.trim(), allowed, field);
        }
    }

    /**
     * 校验枚举值。
     *
     * @param value 枚举值
     * @param allowed 允许值
     * @param field 字段名
     */
    private void validateValue(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw badRequest(field + " 取值非法");
        }
    }

    /**
     * 创建参数错误异常。
     *
     * @param message 错误消息
     * @return 参数错误异常
     */
    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 解析 JSON 字段。
     *
     * @param value JSON 文本
     * @param arrayDefault 是否默认数组
     * @return JSON 节点
     */
    private JsonNode json(String value, boolean arrayDefault) {
        if (!StringUtils.hasText(value)) {
            return arrayDefault ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("知识层 JSON 数据格式非法", exception);
        }
    }

    /**
     * 去除文本首尾空白。
     *
     * @param value 原始文本
     * @return 规范化文本
     */
    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * 转换候选详情。
     *
     * @param entity 候选实体
     * @return 候选详情
     */
    private SettingCandidateDetail candidateDetail(SettingCandidateEntity entity) {
        return new SettingCandidateDetail(
                entity.getId(), entity.getWorkId(), entity.getChapterId(), entity.getSourceType(),
                entity.getSourceId(), entity.getSourceContentRevision(), entity.getSourceStartOffset(),
                entity.getSourceEndOffset(), entity.getSettingType(), entity.getName(), entity.getContent(),
                entity.getCandidateStatus(), entity.getConfirmedSettingId(), entity.getGmtCreate(),
                entity.getGmtModified());
    }

    /**
     * 转换忽略结果。
     *
     * @param entity 候选实体
     * @return 忽略结果
     */
    private IgnoreSettingResult ignoreResult(SettingCandidateEntity entity) {
        return new IgnoreSettingResult(entity.getId(), entity.getCandidateStatus(), entity.getGmtModified());
    }

    /**
     * 转换正式设定详情。
     *
     * @param entity 正式设定实体
     * @return 正式设定详情
     */
    private SettingDetail settingDetail(SettingEntryEntity entity) {
        return new SettingDetail(
                entity.getId(), entity.getWorkId(), entity.getSettingType(), entity.getName(),
                json(entity.getAliasesJson(), true), entity.getContent(), json(entity.getAttributesJson(), false),
                entity.getSourceChapterId(), entity.getSourceCandidateId(), entity.getEntryStatus(),
                entity.getGmtCreate(), entity.getGmtModified());
    }

    /**
     * 转换伏笔详情。
     *
     * @param entity 伏笔实体
     * @return 伏笔详情
     */
    private ForeshadowingDetail foreshadowingDetail(ForeshadowingItemEntity entity) {
        return new ForeshadowingDetail(
                entity.getId(), entity.getWorkId(), entity.getSourceChapterId(), entity.getTitle(),
                entity.getDescription(), entity.getSourceText(), entity.getSourceStartOffset(),
                entity.getSourceEndOffset(), entity.getStatus(), entity.getExpectedPayoffChapterId(),
                entity.getActualPayoffChapterId(), entity.getGmtCreate(), entity.getGmtModified());
    }

    /**
     * 转换章节摘要详情。
     *
     * @param entity 摘要实体
     * @return 摘要详情
     */
    private ChapterSummaryDetail summaryDetail(ChapterSummaryEntity entity) {
        return new ChapterSummaryDetail(
                entity.getId(), entity.getWorkId(), entity.getChapterId(), entity.getSummary(),
                json(entity.getCharacterChangesJson(), true), json(entity.getNewSettingsJson(), true),
                json(entity.getNewForeshadowingJson(), true), json(entity.getOpenQuestionsJson(), true),
                entity.getSummaryStatus(), entity.getContentRevision(), entity.getGmtCreate(),
                entity.getGmtModified());
    }

    /**
     * 转换章节关键事件详情。
     *
     * @param entity 事件实体
     * @return 事件详情
     */
    private ChapterKeyEventDetail eventDetail(ChapterKeyEventEntity entity) {
        return new ChapterKeyEventDetail(
                entity.getId(), entity.getWorkId(), entity.getChapterId(), entity.getEventTitle(),
                entity.getEventContent(), entity.getEventType(), entity.getOccurredOrder(),
                json(entity.getRelatedSettingIdsJson(), true),
                json(entity.getRelatedForeshadowingIdsJson(), true),
                entity.getGmtCreate(), entity.getGmtModified());
    }
}
