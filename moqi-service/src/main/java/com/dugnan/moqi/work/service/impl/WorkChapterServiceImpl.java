/**
 * @author dgn
 * @date:2026-07-13
 * @description:实现作品与章节的查询、创建及打开聚合逻辑。
 */
package com.dugnan.moqi.work.service.impl;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
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

@Service
public class WorkChapterServiceImpl implements WorkChapterService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> WORK_STATUSES = Set.of("draft");
    private static final Set<String> CHAPTER_TYPES =
            Set.of("dedication", "prologue", "chapter", "epilogue", "other");
    private static final Set<String> WORKFLOW_STATUSES = Set.of("co_creation", "done");

    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterConversationMapper conversationMapper;
    private final ChapterGenerationMapper generationMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final SettingCandidateMapper settingCandidateMapper;
    private final SettingEntryMapper settingEntryMapper;
    private final ForeshadowingItemMapper foreshadowingMapper;

    public WorkChapterServiceImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterConversationMapper conversationMapper,
            ChapterGenerationMapper generationMapper,
            ChapterOutlineQueryMapper outlineMapper,
            SettingCandidateMapper settingCandidateMapper,
            SettingEntryMapper settingEntryMapper,
            ForeshadowingItemMapper foreshadowingMapper) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.conversationMapper = conversationMapper;
        this.generationMapper = generationMapper;
        this.outlineMapper = outlineMapper;
        this.settingCandidateMapper = settingCandidateMapper;
        this.settingEntryMapper = settingEntryMapper;
        this.foreshadowingMapper = foreshadowingMapper;
    }

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

    @Override
    @Transactional
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
                chapterCount(workId),
                settingCount,
                foreshadowingCount,
                pendingSettings(workId, null),
                work.getGmtCreate(),
                work.getGmtModified());
    }

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

    @Override
    @Transactional
    public ChapterCreated createChapter(Long workId, CreateChapterCommand command) {
        requireWork(workId);
        String title = validTitle(command == null ? null : command.title());
        String type = command == null || !StringUtils.hasText(command.chapterType())
                ? "chapter"
                : trim(command.chapterType());
        validateOptional(type, CHAPTER_TYPES, "chapterType");

        LambdaQueryWrapper<ChapterEntity> query = new LambdaQueryWrapper<ChapterEntity>()
                .eq(ChapterEntity::getWorkId, workId)
                .eq(ChapterEntity::getDeleted, 0);
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
                saved.getVersion(),
                "co_creation",
                saved.getGmtCreate(),
                saved.getGmtModified());
    }

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
                wordCount(chapter.getContent()),
                chapter.getVersion(),
                chapter.getGmtCreate(),
                chapter.getGmtModified());
    }

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

    private ChapterGenerationEntity findLatestPreview(Long chapterId) {
        LambdaQueryWrapper<ChapterGenerationEntity> query =
                new LambdaQueryWrapper<ChapterGenerationEntity>()
                        .eq(ChapterGenerationEntity::getChapterId, chapterId)
                        .eq(ChapterGenerationEntity::getGenerationStatus, "preview")
                        .eq(ChapterGenerationEntity::getDeleted, 0)
                        .orderByDesc(ChapterGenerationEntity::getGmtModified);
        return generationMapper.selectList(query).stream().findFirst().orElse(null);
    }

    private ChapterConversationEntity findLatestConversation(Long chapterId) {
        LambdaQueryWrapper<ChapterConversationEntity> query =
                new LambdaQueryWrapper<ChapterConversationEntity>()
                        .eq(ChapterConversationEntity::getChapterId, chapterId)
                        .eq(ChapterConversationEntity::getConversationStatus, "active")
                        .eq(ChapterConversationEntity::getDeleted, 0)
                        .orderByDesc(ChapterConversationEntity::getGmtModified);
        return conversationMapper.selectList(query).stream().findFirst().orElse(null);
    }

    private String defaultWorkspace(
            ChapterEntity chapter,
            ChapterGenerationEntity preview) {
        if (preview != null) {
            return "generation_preview";
        }
        if (StringUtils.hasText(chapter.getContent())) {
            return "editor";
        }
        return "co_creation";
    }

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
                chapters.size(),
                id(latest),
                latest == null ? null : latest.getTitle(),
                work.getGmtCreate(),
                work.getGmtModified());
    }

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
                wordCount(chapter.getContent()),
                previewCount > 0,
                chapter.getVersion(),
                chapter.getGmtModified());
    }

    private List<ChapterEntity> chapters(Long workId) {
        return chapterMapper.selectList(
                new LambdaQueryWrapper<ChapterEntity>()
                        .eq(ChapterEntity::getWorkId, workId)
                        .eq(ChapterEntity::getDeleted, 0));
    }

    private long chapterCount(Long workId) {
        return chapterMapper.selectCount(
                new LambdaQueryWrapper<ChapterEntity>()
                        .eq(ChapterEntity::getWorkId, workId)
                        .eq(ChapterEntity::getDeleted, 0));
    }

    private long pendingSettings(Long workId, Long chapterId) {
        return settingCandidateMapper.selectCount(
                new LambdaQueryWrapper<SettingCandidateEntity>()
                        .eq(SettingCandidateEntity::getWorkId, workId)
                        .eq(chapterId != null, SettingCandidateEntity::getChapterId, chapterId)
                        .eq(SettingCandidateEntity::getCandidateStatus, "pending")
                        .eq(SettingCandidateEntity::getDeleted, 0));
    }

    private WorkEntity requireWork(Long id) {
        WorkEntity entity = id == null ? null : workMapper.selectById(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return entity;
    }

    private ChapterEntity requireChapter(Long id) {
        ChapterEntity entity = id == null ? null : chapterMapper.selectById(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return entity;
    }

    private String validTitle(String title) {
        String value = trim(title);
        if (!StringUtils.hasText(value)) {
            throw badRequest("标题不能为空");
        }
        if (value.codePointCount(0, value.length()) > 200) {
            throw badRequest("标题不能超过 200 个字符");
        }
        return value;
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private void validateOptional(String value, Set<String> allowed, String field) {
        if (StringUtils.hasText(value) && !allowed.contains(trim(value))) {
            throw badRequest(field + " 取值非法");
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private int wordCount(String value) {
        if (value == null) {
            return 0;
        }
        return (int) value.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();
    }

    private Long id(BaseEntity entity) {
        return entity == null ? null : entity.getId();
    }
}
