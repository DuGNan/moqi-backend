package com.dugnan.moqi.work.service.impl;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dugnan.moqi.agent.entity.AgentRunEntity;
import com.dugnan.moqi.agent.mapper.AgentRunMapper;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.entity.BaseEntity;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.entity.ForeshadowingItemEntity;
import com.dugnan.moqi.knowledge.entity.SettingCandidateEntity;
import com.dugnan.moqi.knowledge.entity.SettingEntryEntity;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.SettingCandidateMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.work.dto.CreateChapterCommand;
import com.dugnan.moqi.work.dto.CreateWorkCommand;
import com.dugnan.moqi.work.dto.UpdateChapterCommand;
import com.dugnan.moqi.work.dto.UpdateWorkCommand;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterCreated;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterDetail;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterList;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterOpen;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterSummary;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkDetail;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkList;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkRef;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkSummary;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;
import com.dugnan.moqi.work.service.WorkChapterService;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:实现作品与章节的查询、创建及打开聚合逻辑。
 */
@Service
public class WorkChapterServiceImpl implements WorkChapterService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final Set<String> WORK_STATUSES = Set.of("draft");
    private static final Set<String> CHAPTER_TYPES =
            Set.of("dedication", "prologue", "chapter", "epilogue", "other");
    private static final Set<String> WORKFLOW_STATUSES = Set.of("co_creation", "done");
    private static final Set<String> ACTIVE_TASK_STATUSES = Set.of("queued", "running");
    private static final Set<String> ACTIVE_RUN_STATUSES = Set.of("queued", "running", "waiting");

    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterConversationMapper conversationMapper;
    private final ChapterGenerationMapper generationMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final SettingCandidateMapper settingCandidateMapper;
    private final SettingEntryMapper settingEntryMapper;
    private final ForeshadowingItemMapper foreshadowingMapper;
    private final AiTaskMapper aiTaskMapper;
    private final AgentRunMapper agentRunMapper;

    /**
     * 创建作品章节服务。
     *
     * @param workMapper 作品数据访问对象
     * @param chapterMapper 章节数据访问对象
     * @param conversationMapper 章节会话数据访问对象
     * @param generationMapper 章节生成数据访问对象
     * @param outlineMapper 章节大纲查询对象
     * @param settingCandidateMapper 设定候选数据访问对象
     * @param settingEntryMapper 设定数据访问对象
     * @param foreshadowingMapper 伏笔数据访问对象
     */
    public WorkChapterServiceImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterConversationMapper conversationMapper,
            ChapterGenerationMapper generationMapper,
            ChapterOutlineQueryMapper outlineMapper,
            SettingCandidateMapper settingCandidateMapper,
            SettingEntryMapper settingEntryMapper,
            ForeshadowingItemMapper foreshadowingMapper,
            AiTaskMapper aiTaskMapper,
            AgentRunMapper agentRunMapper) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.conversationMapper = conversationMapper;
        this.generationMapper = generationMapper;
        this.outlineMapper = outlineMapper;
        this.settingCandidateMapper = settingCandidateMapper;
        this.settingEntryMapper = settingEntryMapper;
        this.foreshadowingMapper = foreshadowingMapper;
        this.aiTaskMapper = aiTaskMapper;
        this.agentRunMapper = agentRunMapper;
    }

    /**
     * 查询作品列表并应用状态、关键字及数量过滤。
     *
     * @param status 作品状态
     * @param keyword 作品标题关键字
     * @param limit 返回数量上限
     * @return 作品列表
     */
    @Override
    public WorkList listWorks(String status, String keyword, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw badRequest("limit 必须在 1 到 100 之间");
        }
        validateOptional(status, WORK_STATUSES, "status");

        LambdaQueryWrapper<WorkEntity> query = new LambdaQueryWrapper<WorkEntity>()
                .eq(WorkEntity::getDeleted, 0)
                .eq(StringUtils.hasText(status), WorkEntity::getStatus, trim(status))
                .like(StringUtils.hasText(keyword), WorkEntity::getTitle, trim(keyword))
                .orderByDesc(WorkEntity::getGmtModified)
                .last("LIMIT " + size);
        return new WorkList(workMapper.selectList(query).stream().map(this::workSummary).toList());
    }

    /**
     * 创建作品并返回作品摘要。
     *
     * @param command 创建作品命令
     * @return 创建后的作品摘要
    */
    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public WorkSummary createWork(CreateWorkCommand command) {
        String title = validTitle(command == null ? null : command.title());
        WorkEntity entity = new WorkEntity();
        entity.setTitle(title);
        entity.setStatus("draft");
        entity.setDeleted(0);
        entity.setVersion(0);
        workMapper.insert(entity);

        WorkEntity saved = workMapper.selectById(entity.getId());
        return workSummary(saved == null ? entity : saved);
    }

    /**
     * 查询作品详情及其统计信息。
     *
     * @param workId 作品 ID
     * @return 作品详情
     */
    @Override
    public WorkDetail getWork(Long workId) {
        WorkEntity work = requireWork(workId);
        long settingCount = settingEntryMapper.selectCount(
                new LambdaQueryWrapper<SettingEntryEntity>()
                        .eq(SettingEntryEntity::getWorkId, workId)
                        .eq(SettingEntryEntity::getDeleted, 0));
        long foreshadowingCount = foreshadowingMapper.selectCount(
                new LambdaQueryWrapper<ForeshadowingItemEntity>()
                        .eq(ForeshadowingItemEntity::getWorkId, workId)
                        .eq(ForeshadowingItemEntity::getDeleted, 0));

        return new WorkDetail(
                work.getId(),
                work.getTitle(),
                work.getStatus(),
                work.getVersion(),
                chapterCount(workId),
                settingCount,
                foreshadowingCount,
                pendingSettings(workId, null),
                work.getGmtCreate(),
                work.getGmtModified());
    }

    /**
     * 按客户端版本修改作品标题。
     *
     * @param workId 作品 ID
     * @param command 标题和基础版本
     * @return 更新后的作品详情
     */
    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public WorkDetail updateWork(Long workId, UpdateWorkCommand command) {
        String title = validTitle(command == null ? null : command.title());
        Integer baseVersion = requiredBaseVersion(command == null ? null : command.baseVersion());
        requireWork(workId);
        if (workMapper.updateTitleIfVersion(workId, title, baseVersion) != 1) {
            throw workVersionConflict(workId);
        }
        return getWork(workId);
    }

    /**
     * 逻辑删除作品及其未删除章节。
     *
     * @param workId 作品 ID
     * @param baseVersion 客户端基础版本
     */
    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public void deleteWork(Long workId, Integer baseVersion) {
        Integer expectedVersion = requiredBaseVersion(baseVersion);
        WorkEntity work = requireLockedWork(workId);
        requireMatchingWorkVersion(work, expectedVersion);
        lockActiveChapters(workId);
        requireNoActiveTasks(workId, null);
        if (workMapper.softDeleteIfVersion(workId, expectedVersion) != 1) {
            throw workVersionConflict(workId);
        }
        chapterMapper.softDeleteActiveByWorkId(workId);
    }

    /**
     * 查询作品下的章节列表。
     *
     * @param workId 作品 ID
     * @param chapterType 章节类型
     * @param workflowStatus 工作流状态
     * @param keyword 章节标题关键字
     * @return 章节列表
     */
    @Override
    public ChapterList listChapters(
            Long workId,
            String chapterType,
            String workflowStatus,
            String keyword) {
        WorkEntity work = requireWork(workId);
        validateOptional(chapterType, CHAPTER_TYPES, "chapterType");
        validateOptional(workflowStatus, WORKFLOW_STATUSES, "workflowStatus");

        LambdaQueryWrapper<ChapterEntity> query = new LambdaQueryWrapper<ChapterEntity>()
                .eq(ChapterEntity::getWorkId, workId)
                .eq(ChapterEntity::getDeleted, 0)
                .eq(StringUtils.hasText(chapterType), ChapterEntity::getChapterType, trim(chapterType))
                .eq(
                        StringUtils.hasText(workflowStatus),
                        ChapterEntity::getWorkflowStatus,
                        trim(workflowStatus))
                .like(StringUtils.hasText(keyword), ChapterEntity::getTitle, trim(keyword))
                .orderByAsc(ChapterEntity::getChapterNo);
        List<ChapterSummary> summaries = chapterMapper.selectList(query).stream()
                .map(this::chapterSummary)
                .toList();
        return new ChapterList(new WorkRef(workId, work.getTitle()), summaries);
    }

    /**
     * 创建章节并分配下一个章节编号。
     *
     * @param workId 作品 ID
     * @param command 创建章节命令
     * @return 创建后的章节信息
    */
    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ChapterCreated createChapter(Long workId, CreateChapterCommand command) {
        requireLockedWork(workId);
        String title = validTitle(command == null ? null : command.title());
        String type = command == null || !StringUtils.hasText(command.chapterType())
                ? "chapter"
                : trim(command.chapterType());
        validateOptional(type, CHAPTER_TYPES, "chapterType");

        LambdaQueryWrapper<ChapterEntity> query = new LambdaQueryWrapper<ChapterEntity>()
                .eq(ChapterEntity::getWorkId, workId);
        List<ChapterEntity> chapters = chapterMapper.selectList(query);
        int nextChapterNumber = chapters.stream()
                .map(ChapterEntity::getChapterNo)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        ChapterEntity entity = new ChapterEntity();
        entity.setWorkId(workId);
        entity.setTitle(title);
        entity.setChapterNo(nextChapterNumber);
        entity.setChapterType(type);
        entity.setWorkflowStatus("co_creation");
        entity.setDeleted(0);
        entity.setVersion(0);
        chapterMapper.insert(entity);

        ChapterEntity saved = chapterMapper.selectById(entity.getId());
        if (saved == null) {
            saved = entity;
        }
        return new ChapterCreated(
                saved.getId(),
                workId,
                saved.getTitle(),
                saved.getChapterNo(),
                saved.getChapterType(),
                saved.getWorkflowStatus(),
                saved.getCurrentProseRevisionId(),
                saved.getVersion(),
                "co_creation",
                saved.getGmtCreate(),
                saved.getGmtModified());
    }

    /**
     * 查询章节详情。
     *
     * @param chapterId 章节 ID
     * @return 章节详情
     */
    @Override
    public ChapterDetail getChapter(Long chapterId) {
        ChapterEntity chapter = requireChapter(chapterId);
        WorkEntity work = requireWork(chapter.getWorkId());
        return new ChapterDetail(
                chapter.getId(),
                chapter.getWorkId(),
                work.getTitle(),
                chapter.getTitle(),
                chapter.getChapterNo(),
                chapter.getChapterType(),
                chapter.getWorkflowStatus(),
                chapter.getCurrentProseRevisionId(),
                wordCount(chapter.getContent()),
                chapter.getVersion(),
                chapter.getGmtCreate(),
                chapter.getGmtModified());
    }

    /**
     * 按客户端版本修改章节标题。
     *
     * @param chapterId 章节 ID
     * @param command 标题和基础版本
     * @return 更新后的章节详情
     */
    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ChapterDetail updateChapter(Long chapterId, UpdateChapterCommand command) {
        String title = validTitle(command == null ? null : command.title());
        Integer baseVersion = requiredBaseVersion(command == null ? null : command.baseVersion());
        ChapterEntity chapter = requireChapter(chapterId);
        requireWork(chapter.getWorkId());
        if (chapterMapper.updateTitleIfVersion(chapterId, title, baseVersion) != 1) {
            throw chapterVersionConflict(chapterId);
        }
        return getChapter(chapterId);
    }

    /**
     * 逻辑删除单个章节。
     *
     * @param chapterId 章节 ID
     * @param baseVersion 客户端基础版本
     */
    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public void deleteChapter(Long chapterId, Integer baseVersion) {
        Integer expectedVersion = requiredBaseVersion(baseVersion);
        ChapterEntity existing = requireChapter(chapterId);
        requireLockedWork(existing.getWorkId());
        ChapterEntity chapter = requireLockedChapter(chapterId);
        requireMatchingChapterVersion(chapter, expectedVersion);
        requireNoActiveTasks(chapter.getWorkId(), chapterId);
        if (chapterMapper.softDeleteIfVersion(chapterId, expectedVersion) != 1) {
            throw chapterVersionConflict(chapterId);
        }
    }

    /**
     * 聚合章节相关状态并计算默认工作区。
     *
     * @param chapterId 章节 ID
     * @return 章节打开建议
     */
    @Override
    public ChapterOpen openChapter(Long chapterId) {
        ChapterEntity chapter = requireChapter(chapterId);
        requireWork(chapter.getWorkId());

        ChapterGenerationEntity preview = findLatestPreview(chapterId);
        ChapterConversationEntity conversation = findLatestConversation(chapterId);
        ChapterOutlineEntity outline = outlineMapper.findLatest(chapterId);
        String workspace = defaultWorkspace(chapter, preview);

        return new ChapterOpen(
                chapter.getWorkId(),
                chapterId,
                workspace,
                id(conversation),
                id(preview),
                chapter.getVersion(),
                outline == null ? null : outline.getId(),
                outline == null ? null : outline.getRevision(),
                pendingSettings(chapter.getWorkId(), chapterId),
                chapter.getGmtModified());
    }

    /**
     * 查询章节最新的预览生成记录。
     *
     * @param chapterId 章节 ID
     * @return 最新预览记录，不存在时返回 null
     */
    private ChapterGenerationEntity findLatestPreview(Long chapterId) {
        LambdaQueryWrapper<ChapterGenerationEntity> query =
                new LambdaQueryWrapper<ChapterGenerationEntity>()
                        .eq(ChapterGenerationEntity::getChapterId, chapterId)
                        .eq(ChapterGenerationEntity::getGenerationStatus, "preview")
                        .eq(ChapterGenerationEntity::getDeleted, 0)
                        .orderByDesc(ChapterGenerationEntity::getGmtModified);
        return generationMapper.selectList(query).stream().findFirst().orElse(null);
    }

    /**
     * 查询章节最新的活动会话。
     *
     * @param chapterId 章节 ID
     * @return 最新活动会话，不存在时返回 null
     */
    private ChapterConversationEntity findLatestConversation(Long chapterId) {
        LambdaQueryWrapper<ChapterConversationEntity> query =
                new LambdaQueryWrapper<ChapterConversationEntity>()
                        .eq(ChapterConversationEntity::getChapterId, chapterId)
                        .eq(ChapterConversationEntity::getConversationStatus, "active")
                        .eq(ChapterConversationEntity::getDeleted, 0)
                        .orderByDesc(ChapterConversationEntity::getGmtModified);
        return conversationMapper.selectList(query).stream().findFirst().orElse(null);
    }

    /**
     * 根据章节上下文计算默认工作区。
     *
     * @param chapter 章节实体
     * @param preview 最新预览生成记录
     * @return 默认工作区标识
     */
    private String defaultWorkspace(
            ChapterEntity chapter,
            ChapterGenerationEntity preview) {
        if (StringUtils.hasText(chapter.getContent())) {
            return "editor";
        }
        if (preview != null) {
            return "generation_preview";
        }
        return "co_creation";
    }

    /**
     * 将作品实体转换为作品摘要。
     *
     * @param work 作品实体
     * @return 作品摘要
     */
    private WorkSummary workSummary(WorkEntity work) {
        List<ChapterEntity> chapters = chapters(work.getId());
        ChapterEntity latest = chapters.stream()
                .max(
                        Comparator.comparing(
                                        ChapterEntity::getGmtModified,
                                        Comparator.nullsFirst(Comparator.naturalOrder()))
                                .thenComparing(ChapterEntity::getId))
                .orElse(null);
        return new WorkSummary(
                work.getId(),
                work.getTitle(),
                work.getStatus(),
                work.getVersion(),
                chapters.size(),
                id(latest),
                latest == null ? null : latest.getTitle(),
                work.getGmtCreate(),
                work.getGmtModified());
    }

    /**
     * 将章节实体转换为章节摘要。
     *
     * @param chapter 章节实体
     * @return 章节摘要
     */
    private ChapterSummary chapterSummary(ChapterEntity chapter) {
        long previewCount = generationMapper.selectCount(
                new LambdaQueryWrapper<ChapterGenerationEntity>()
                        .eq(ChapterGenerationEntity::getChapterId, chapter.getId())
                        .eq(ChapterGenerationEntity::getGenerationStatus, "preview")
                        .eq(ChapterGenerationEntity::getDeleted, 0));
        return new ChapterSummary(
                chapter.getId(),
                chapter.getWorkId(),
                chapter.getTitle(),
                chapter.getChapterNo(),
                chapter.getChapterType(),
                chapter.getWorkflowStatus(),
                chapter.getCurrentProseRevisionId(),
                wordCount(chapter.getContent()),
                previewCount > 0,
                chapter.getVersion(),
                chapter.getGmtModified());
    }

    /**
     * 查询作品下所有未删除章节。
     *
     * @param workId 作品 ID
     * @return 未删除章节列表
     */
    private List<ChapterEntity> chapters(Long workId) {
        return chapterMapper.selectList(
                new LambdaQueryWrapper<ChapterEntity>()
                        .eq(ChapterEntity::getWorkId, workId)
                        .eq(ChapterEntity::getDeleted, 0));
    }

    /**
     * 统计作品下未删除章节数量。
     *
     * @param workId 作品 ID
     * @return 章节数量
     */
    private long chapterCount(Long workId) {
        return chapterMapper.selectCount(
                new LambdaQueryWrapper<ChapterEntity>()
                        .eq(ChapterEntity::getWorkId, workId)
                        .eq(ChapterEntity::getDeleted, 0));
    }

    /**
     * 统计待确认设定数量。
     *
     * @param workId 作品 ID
     * @param chapterId 章节 ID，可为空
     * @return 待确认设定数量
     */
    private long pendingSettings(Long workId, Long chapterId) {
        return settingCandidateMapper.selectCount(
                new LambdaQueryWrapper<SettingCandidateEntity>()
                        .eq(SettingCandidateEntity::getWorkId, workId)
                        .eq(chapterId != null, SettingCandidateEntity::getChapterId, chapterId)
                        .eq(SettingCandidateEntity::getCandidateStatus, "pending")
                        .eq(SettingCandidateEntity::getDeleted, 0));
    }

    /**
     * 获取未删除作品，不存在时抛出业务异常。
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

    private WorkEntity requireLockedWork(Long workId) {
        WorkEntity entity = workId == null ? null : workMapper.selectByIdForUpdate(workId);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return entity;
    }

    /**
     * 获取未删除章节，不存在时抛出业务异常。
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

    private ChapterEntity requireLockedChapter(Long chapterId) {
        ChapterEntity entity = chapterId == null ? null : chapterMapper.selectByIdForUpdate(chapterId);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return entity;
    }

    private void lockActiveChapters(Long workId) {
        chapterMapper.selectList(new LambdaQueryWrapper<ChapterEntity>()
                .eq(ChapterEntity::getWorkId, workId)
                .eq(ChapterEntity::getDeleted, 0)
                .orderByAsc(ChapterEntity::getId)
                .last("FOR UPDATE"));
    }

    private void requireNoActiveTasks(Long workId, Long chapterId) {
        long activeTasks = aiTaskMapper.selectCount(new LambdaQueryWrapper<AiTaskEntity>()
                .eq(AiTaskEntity::getWorkId, workId)
                .eq(chapterId != null, AiTaskEntity::getChapterId, chapterId)
                .eq(AiTaskEntity::getDeleted, 0)
                .in(AiTaskEntity::getTaskStatus, ACTIVE_TASK_STATUSES));
        long activeRuns = agentRunMapper.selectCount(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getWorkId, workId)
                .eq(chapterId != null, AgentRunEntity::getChapterId, chapterId)
                .eq(AgentRunEntity::getDeleted, 0)
                .in(AgentRunEntity::getRunStatus, ACTIVE_RUN_STATUSES));
        if (activeTasks > 0 || activeRuns > 0) {
            throw new BusinessException(ErrorCode.AI_TASK_STATE_CONFLICT, "存在未结束的 AI 任务，请先取消或等待任务完成");
        }
    }

    private Integer requiredBaseVersion(Integer baseVersion) {
        if (baseVersion == null || baseVersion < 0) {
            throw badRequest("baseVersion 必须为非负整数");
        }
        return baseVersion;
    }

    private void requireMatchingWorkVersion(WorkEntity work, Integer baseVersion) {
        if (!baseVersion.equals(work.getVersion())) {
            throw workVersionConflict(work.getId());
        }
    }

    private void requireMatchingChapterVersion(ChapterEntity chapter, Integer baseVersion) {
        if (!baseVersion.equals(chapter.getVersion())) {
            throw chapterVersionConflict(chapter.getId());
        }
    }

    private BusinessException workVersionConflict(Long workId) {
        WorkEntity current = requireWork(workId);
        return new BusinessException(
                ErrorCode.WORK_VERSION_CONFLICT,
                "作品已被更新，请刷新后重试",
                java.util.Map.of("version", current.getVersion(), "title", current.getTitle()));
    }

    private BusinessException chapterVersionConflict(Long chapterId) {
        ChapterEntity current = requireChapter(chapterId);
        return new BusinessException(
                ErrorCode.CHAPTER_VERSION_CONFLICT,
                "章节已被更新，请刷新后重试",
                java.util.Map.of("version", current.getVersion(), "title", current.getTitle()));
    }

    /**
     * 校验并规范化标题。
     *
     * @param title 原始标题
     * @return 去除首尾空白后的标题
     */
    private String validTitle(String title) {
        String value = trim(title);
        if (!StringUtils.hasText(value)) {
            throw badRequest("标题不能为空");
        }
        if (value.codePointCount(0, value.length()) > MAX_TITLE_LENGTH) {
            throw badRequest("标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        }
        return value;
    }

    /**
     * 创建请求参数错误异常。
     *
     * @param message 错误消息
     * @return 请求参数错误异常
     */
    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 校验可选枚举参数是否在允许范围内。
     *
     * @param value 待校验值
     * @param allowed 允许值集合
     * @param field 字段名称
     */
    private void validateOptional(String value, Set<String> allowed, String field) {
        if (StringUtils.hasText(value) && !allowed.contains(trim(value))) {
            throw badRequest(field + " 取值非法");
        }
    }

    /**
     * 去除字符串首尾空白。
     *
     * @param value 原始字符串
     * @return 规范化字符串
     */
    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * 统计字符串中的非空白 Unicode 码点数量。
     *
     * @param value 原始文本
     * @return 非空白字符数量
     */
    private int wordCount(String value) {
        if (value == null) {
            return 0;
        }
        return (int) value.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();
    }

    /**
     * 安全获取实体 ID。
     *
     * @param entity 基础实体
     * @return 实体 ID，不存在时返回 null
     */
    private Long id(BaseEntity entity) {
        return entity == null ? null : entity.getId();
    }
}
