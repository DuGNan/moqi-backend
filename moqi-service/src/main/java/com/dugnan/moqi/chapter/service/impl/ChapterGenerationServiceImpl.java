package com.dugnan.moqi.chapter.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.AcceptGenerationRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.ChapterContent;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.ContentSaved;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.CreateGenerationRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationAccepted;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationCreated;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationDetail;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationRejected;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.LatestPreview;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.RegenerateRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.RejectGenerationRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.SaveContentRequest;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.event.ChapterGenerationAcceptedEvent;
import com.dugnan.moqi.chapter.generator.ChapterContentGenerator;
import com.dugnan.moqi.chapter.generator.ChapterContentGenerator.GenerationInput;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.service.ChapterGenerationService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 实现章节生成记录创建与本地生成状态流转。
 */
@Service
public class ChapterGenerationServiceImpl implements ChapterGenerationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChapterGenerationServiceImpl.class);
    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_PREVIEW = "preview";
    private static final String STATUS_ACCEPTED = "accepted";
    private static final String STATUS_REJECTED = "rejected";
    private static final String TASK_TYPE = "chapter_generation";
    private static final String TASK_STATUS_QUEUED = "queued";
    private static final String TASK_STATUS_SUCCEEDED = "succeeded";
    private static final String CUSTOM_LENGTH_PRESET = "custom";
    private static final String REPLACE_APPLY_MODE = "replace";
    private static final int MINIMUM_CUSTOM_WORD_COUNT = 1;
    private static final int MAXIMUM_CUSTOM_WORD_COUNT = 100000;
    private static final char JSON_CONTROL_CHARACTER_LIMIT = 0x20;
    private static final char JSON_QUOTATION_MARK = '"';
    private static final char JSON_REVERSE_SOLIDUS = '\\';
    private static final char JSON_BACKSPACE = '\b';
    private static final char JSON_FORM_FEED = '\f';
    private static final char JSON_LINE_FEED = '\n';
    private static final char JSON_CARRIAGE_RETURN = '\r';
    private static final char JSON_TAB = '\t';
    private static final Set<String> GENERATION_MODES = Set.of("full_draft", "segmented_draft");
    private static final Set<String> LENGTH_PRESETS = Set.of("about_3000", "custom");
    private static final Set<String> APPLY_MODES = Set.of("replace", "append");
    private static final Set<String> SAVE_SOURCES =
            Set.of("manual", "auto_save", "generation_accept", "patch_apply");

    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final ChapterBriefMapper briefMapper;
    private final ChapterGenerationMapper generationMapper;
    private final AiTaskMapper aiTaskMapper;
    private final ChapterContentGenerator contentGenerator;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建章节生成服务。
     *
     * @param workMapper 作品数据访问对象
     * @param chapterMapper 章节数据访问对象
     * @param outlineMapper 大纲数据访问对象
     * @param briefMapper brief 数据访问对象
     * @param generationMapper 生成记录数据访问对象
     * @param aiTaskMapper AI 任务数据访问对象
     * @param contentGenerator 正文生成器
     * @param eventPublisher 事务提交后的领域事件发布器
     */
    public ChapterGenerationServiceImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterOutlineQueryMapper outlineMapper,
            ChapterBriefMapper briefMapper,
            ChapterGenerationMapper generationMapper,
            AiTaskMapper aiTaskMapper,
            ChapterContentGenerator contentGenerator,
            ApplicationEventPublisher eventPublisher) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.outlineMapper = outlineMapper;
        this.briefMapper = briefMapper;
        this.generationMapper = generationMapper;
        this.aiTaskMapper = aiTaskMapper;
        this.contentGenerator = contentGenerator;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public GenerationCreated createGeneration(Long chapterId, CreateGenerationRequest request) {
        ChapterEntity chapter = requireChapter(chapterId);
        WorkEntity work = requireWork(chapter.getWorkId());
        ChapterOutlineEntity outline = requireOutline(chapterId, request);
        ChapterBriefEntity brief =
                requireConfirmedBrief(chapterId, request.confirmedBriefId());
        if (outline.getConfirmedBriefId() != null
                && !outline.getConfirmedBriefId().equals(brief.getId())) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_CONFIRMED_BRIEF_REQUIRED,
                    "大纲绑定的已确认 Brief 已变化，请先刷新或保存大纲");
        }
        GenerationOptions options = options(
                request.generationMode(),
                request.lengthPreset(),
                request.customWordCount(),
                "full_draft",
                "about_3000");

        AiTaskEntity task = createTask(chapter);
        ChapterGenerationEntity generation = createDraft(
                chapter,
                outline,
                brief,
                options,
                task.getId(),
                basisSnapshot(chapter, outline, brief, options, null));
        generationMapper.insert(generation);

        String generatedContent = contentGenerator.generate(new GenerationInput(
                work.getTitle(),
                chapter.getTitle(),
                brief.getBriefContent(),
                outline.getOutlineContent(),
                options.generationMode(),
                options.lengthPreset(),
                options.customWordCount(),
                null));
        completeGeneration(generation, task, generatedContent);
        ChapterGenerationEntity persisted = requireGeneration(generation.getId());

        return new GenerationCreated(
                persisted.getId(),
                task.getId(),
                chapter.getWorkId(),
                chapterId,
                STATUS_DRAFT,
                persisted.getGmtCreate());
    }

    @Override
    public GenerationDetail getGeneration(Long generationId) {
        return generationDetail(requireGeneration(generationId));
    }

    @Override
    public LatestPreview getLatestPreview(Long chapterId) {
        requireChapter(chapterId);
        ChapterGenerationEntity preview = generationMapper.findLatestPreview(chapterId);
        if (preview == null) {
            return new LatestPreview(null, chapterId, null, null, null, null);
        }
        return new LatestPreview(
                preview.getId(),
                preview.getChapterId(),
                preview.getGenerationStatus(),
                preview.getGenerationMode(),
                preview.getWordCount(),
                preview.getGmtCreate());
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public GenerationAccepted acceptGeneration(Long generationId, AcceptGenerationRequest request) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        if (STATUS_ACCEPTED.equals(generation.getGenerationStatus())) {
            eventPublisher.publishEvent(
                    new ChapterGenerationAcceptedEvent(generation.getChapterId(), generation.getId()));
            return accepted(generation, requireChapter(generation.getChapterId()));
        }
        requirePreview(generation);
        ChapterEntity chapter = requireChapter(generation.getChapterId());
        String applyMode = requiredAllowed(
                request == null ? null : request.applyMode(),
                APPLY_MODES,
                "applyMode");
        Integer baseVersion = request == null ? null : request.baseVersion();
        if (baseVersion == null) {
            throw badRequest("baseVersion 不能为空");
        }
        String content = applyContent(chapter.getContent(), generation.getGeneratedContent(), applyMode);
        if (generationMapper.updateStatusIfCurrent(generationId, STATUS_PREVIEW, STATUS_ACCEPTED) != 1) {
            throw statusConflict();
        }
        if (chapterMapper.updateContentIfVersion(chapter.getId(), content, baseVersion) != 1) {
            throw versionConflict(requireChapter(chapter.getId()));
        }
        generationMapper.supersedeOlderPreviews(chapter.getId(), generationId);
        generation.setGenerationStatus(STATUS_ACCEPTED);
        eventPublisher.publishEvent(new ChapterGenerationAcceptedEvent(chapter.getId(), generation.getId()));
        return accepted(generation, requireChapter(chapter.getId()));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public GenerationRejected rejectGeneration(Long generationId, RejectGenerationRequest request) {
        ChapterGenerationEntity generation = requireGeneration(generationId);
        requirePreview(generation);
        if (generationMapper.updateStatusIfCurrent(generationId, STATUS_PREVIEW, STATUS_REJECTED) != 1) {
            throw statusConflict();
        }
        ChapterGenerationEntity rejected = requireGeneration(generationId);
        return new GenerationRejected(rejected.getId(), rejected.getGenerationStatus(), rejected.getGmtModified());
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public GenerationCreated regenerate(Long generationId, RegenerateRequest request) {
        ChapterGenerationEntity original = requireGeneration(generationId);
        if (STATUS_DRAFT.equals(original.getGenerationStatus())) {
            throw statusConflict();
        }
        ChapterEntity chapter = requireChapter(original.getChapterId());
        WorkEntity work = requireWork(chapter.getWorkId());
        Map<String, Object> originalBasis = parseSnapshot(original.getId(), original.getBasisSnapshotJson());
        String snapshotChapterTitle = snapshotText(
                originalBasis.get("chapterTitle"),
                chapter.getTitle());
        String feedback = optionalText(request == null ? null : request.feedback());
        GenerationOptions options = options(
                request == null ? null : request.generationMode(),
                request == null ? null : request.lengthPreset(),
                request == null ? null : request.customWordCount(),
                original.getGenerationMode(),
                original.getLengthPreset());

        AiTaskEntity task = createTask(chapter);
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setWorkId(original.getWorkId());
        generation.setChapterId(original.getChapterId());
        generation.setBriefId(original.getBriefId());
        generation.setOutlineId(original.getOutlineId());
        generation.setOutlineRevision(original.getOutlineRevision());
        generation.setGenerationStatus(STATUS_DRAFT);
        generation.setGenerationMode(options.generationMode());
        generation.setLengthPreset(options.lengthPreset());
        generation.setCustomWordCount(options.customWordCount());
        generation.setBasisSnapshotJson(regeneratedSnapshot(
                originalBasis,
                snapshotChapterTitle,
                options,
                feedback));
        generation.setWordCount(0);
        generation.setAiTaskId(task.getId());
        generation.setDeleted(0);
        generation.setVersion(0);
        generationMapper.insert(generation);

        String outlineContent = String.valueOf(originalBasis.getOrDefault("outlineContent", ""));
        String briefContent = String.valueOf(originalBasis.getOrDefault("briefContent", ""));
        String generatedContent = contentGenerator.generate(new GenerationInput(
                work.getTitle(),
                snapshotChapterTitle,
                briefContent,
                outlineContent,
                options.generationMode(),
                options.lengthPreset(),
                options.customWordCount(),
                feedback));
        completeGeneration(generation, task, generatedContent);
        ChapterGenerationEntity persisted = requireGeneration(generation.getId());
        return new GenerationCreated(
                persisted.getId(),
                task.getId(),
                generation.getWorkId(),
                generation.getChapterId(),
                STATUS_DRAFT,
                persisted.getGmtCreate());
    }

    @Override
    public ChapterContent getContent(Long chapterId) {
        return chapterContent(requireChapter(chapterId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ContentSaved saveContent(Long chapterId, SaveContentRequest request) {
        ChapterEntity chapter = requireChapter(chapterId);
        if (request == null || request.content() == null || request.baseVersion() == null) {
            throw badRequest("content 和 baseVersion 不能为空");
        }
        requiredAllowed(request.saveSource(), SAVE_SOURCES, "saveSource");
        if (chapterMapper.updateContentIfVersion(chapterId, request.content(), request.baseVersion()) != 1) {
            throw versionConflict(requireChapter(chapterId));
        }
        ChapterEntity saved = requireChapter(chapterId);
        return new ContentSaved(
                chapterId,
                true,
                saved.getVersion(),
                false,
                wordCount(saved.getContent()),
                saved.getGmtModified());
    }

    private AiTaskEntity createTask(ChapterEntity chapter) {
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(TASK_TYPE);
        task.setTaskStatus(TASK_STATUS_QUEUED);
        task.setWorkId(chapter.getWorkId());
        task.setChapterId(chapter.getId());
        task.setDeleted(0);
        task.setVersion(0);
        aiTaskMapper.insert(task);
        return task;
    }

    private ChapterGenerationEntity createDraft(
            ChapterEntity chapter,
            ChapterOutlineEntity outline,
            ChapterBriefEntity brief,
            GenerationOptions options,
            Long aiTaskId,
            String basisSnapshotJson) {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setWorkId(chapter.getWorkId());
        generation.setChapterId(chapter.getId());
        generation.setBriefId(brief == null ? null : brief.getId());
        generation.setOutlineId(outline.getId());
        generation.setOutlineRevision(outline.getRevision());
        generation.setGenerationStatus(STATUS_DRAFT);
        generation.setGenerationMode(options.generationMode());
        generation.setLengthPreset(options.lengthPreset());
        generation.setCustomWordCount(options.customWordCount());
        generation.setBasisSnapshotJson(basisSnapshotJson);
        generation.setWordCount(0);
        generation.setAiTaskId(aiTaskId);
        generation.setDeleted(0);
        generation.setVersion(0);
        return generation;
    }

    private ChapterOutlineEntity requireOutline(Long chapterId, CreateGenerationRequest request) {
        if (request == null || request.outlineId() == null || request.baseRevision() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "outlineId 和 baseRevision 不能为空");
        }
        ChapterOutlineEntity outline = outlineMapper.findLatest(chapterId);
        if (outline == null || !request.outlineId().equals(outline.getId())) {
            throw new BusinessException(ErrorCode.OUTLINE_NOT_FOUND, "章节大纲不存在");
        }
        if (!request.baseRevision().equals(outline.getRevision())) {
            throw new BusinessException(ErrorCode.OUTLINE_REVISION_CONFLICT, "大纲已被更新，请刷新后重试");
        }
        return outline;
    }

    private ChapterBriefEntity requireConfirmedBrief(Long chapterId, Long confirmedBriefId) {
        ChapterBriefEntity brief = confirmedBriefId == null
                ? briefMapper.findLatestByChapterIdAndStatus(chapterId, STATUS_CONFIRMED)
                : briefMapper.findByIdAndChapterId(confirmedBriefId, chapterId);
        if (brief == null) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_CONFIRMED_BRIEF_REQUIRED,
                    "请先确认本章 Brief，再生成章节正文");
        }
        if (!STATUS_CONFIRMED.equals(brief.getBriefStatus())) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_CONFIRMED_BRIEF_REQUIRED,
                    "章节生成只能使用已确认 Brief");
        }
        return brief;
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private WorkEntity requireWork(Long workId) {
        WorkEntity work = workId == null ? null : workMapper.selectById(workId);
        if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return work;
    }

    private ChapterGenerationEntity requireGeneration(Long generationId) {
        ChapterGenerationEntity generation =
                generationId == null ? null : generationMapper.selectById(generationId);
        if (generation == null || Integer.valueOf(1).equals(generation.getDeleted())) {
            throw new BusinessException(ErrorCode.GENERATION_NOT_FOUND, "生成记录不存在");
        }
        return generation;
    }

    private void completeGeneration(
            ChapterGenerationEntity generation,
            AiTaskEntity task,
            String generatedContent) {
        generation.setGeneratedContent(generatedContent);
        generation.setWordCount(wordCount(generatedContent));
        generation.setGenerationStatus(STATUS_PREVIEW);
        generationMapper.updateById(generation);
        task.setResultGenerationId(generation.getId());
        task.setTaskStatus(TASK_STATUS_SUCCEEDED);
        aiTaskMapper.updateById(task);
    }

    private GenerationOptions options(
            String generationMode,
            String lengthPreset,
            Integer customWordCount,
            String defaultMode,
            String defaultPreset) {
        String mode = StringUtils.hasText(generationMode) ? generationMode.trim() : defaultMode;
        String preset = StringUtils.hasText(lengthPreset) ? lengthPreset.trim() : defaultPreset;
        if (!GENERATION_MODES.contains(mode)) {
            throw badRequest("generationMode 取值非法");
        }
        if (!LENGTH_PRESETS.contains(preset)) {
            throw badRequest("lengthPreset 取值非法");
        }
        if (customWordCount != null && customWordCount < MINIMUM_CUSTOM_WORD_COUNT) {
            throw invalidCustomWordCount();
        }
        if (customWordCount != null && customWordCount > MAXIMUM_CUSTOM_WORD_COUNT) {
            throw invalidCustomWordCount();
        }
        if (CUSTOM_LENGTH_PRESET.equals(preset) && customWordCount == null) {
            throw badRequest("custom 长度预设必须提供 customWordCount");
        }
        return new GenerationOptions(mode, preset, customWordCount);
    }

    private String basisSnapshot(
            ChapterEntity chapter,
            ChapterOutlineEntity outline,
            ChapterBriefEntity brief,
            GenerationOptions options,
            String feedback) {
        return "{\"outlineId\":" + outline.getId()
                + ",\"outlineRevision\":" + outline.getRevision()
                + ",\"confirmedBriefId\":" + brief.getId()
                + ",\"confirmedBriefVersion\":" + brief.getVersion()
                + ",\"chapterTitle\":\"" + json(chapter.getTitle())
                + "\",\"briefContent\":\"" + json(brief.getBriefContent())
                + "\",\"outlineContent\":\"" + json(outline.getOutlineContent())
                + "\",\"generationMode\":\"" + json(options.generationMode())
                + "\",\"lengthPreset\":\"" + json(options.lengthPreset())
                + "\",\"customWordCount\":" + number(options.customWordCount())
                + ",\"feedback\":\"" + json(feedback) + "\"}";
    }

    private String regeneratedSnapshot(
            Map<String, Object> originalBasis,
            String chapterTitle,
            GenerationOptions options,
            String feedback) {
        return "{\"outlineId\":" + number(originalBasis.get("outlineId"))
                + ",\"outlineRevision\":" + number(originalBasis.get("outlineRevision"))
                + ",\"confirmedBriefId\":" + number(originalBasis.get("confirmedBriefId"))
                + ",\"confirmedBriefVersion\":" + number(originalBasis.get("confirmedBriefVersion"))
                + ",\"chapterTitle\":\"" + json(chapterTitle)
                + "\",\"briefContent\":\"" + json(stringValue(originalBasis.get("briefContent")))
                + "\",\"outlineContent\":\"" + json(stringValue(originalBasis.get("outlineContent")))
                + "\",\"generationMode\":\"" + json(options.generationMode())
                + "\",\"lengthPreset\":\"" + json(options.lengthPreset())
                + "\",\"customWordCount\":" + number(options.customWordCount())
                + ",\"feedback\":\"" + json(feedback) + "\"}";
    }

    private Map<String, Object> parseSnapshot(Long generationId, String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            return JsonParserFactory.getJsonParser().parseMap(value);
        } catch (IllegalArgumentException exception) {
            LOGGER.error("生成依据快照解析失败，generationId={}", generationId);
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "生成依据快照无法读取",
                    exception);
        }
    }

    private GenerationDetail generationDetail(ChapterGenerationEntity generation) {
        return new GenerationDetail(
                generation.getId(),
                generation.getWorkId(),
                generation.getChapterId(),
                generation.getOutlineId(),
                generation.getOutlineRevision(),
                generation.getGenerationStatus(),
                generation.getGenerationMode(),
                generation.getLengthPreset(),
                generation.getCustomWordCount(),
                parseSnapshot(generation.getId(), generation.getBasisSnapshotJson()),
                generation.getGeneratedContent(),
                generation.getWordCount(),
                generation.getAiTaskId(),
                StringUtils.hasText(generation.getContentAssemblyMode())
                        ? generation.getContentAssemblyMode() : "scene_join_legacy",
                StringUtils.hasText(generation.getCohesionStatus())
                        ? generation.getCohesionStatus() : "not_applicable",
                generation.getCohesionModelCallId(),
                generation.getCohesionTemplateVersion(),
                generation.getGmtCreate(),
                generation.getGmtModified());
    }

    private GenerationAccepted accepted(
            ChapterGenerationEntity generation,
            ChapterEntity chapter) {
        return new GenerationAccepted(
                chapter.getWorkId(),
                chapter.getId(),
                generation.getId(),
                STATUS_ACCEPTED,
                chapter.getVersion(),
                chapter.getWorkflowStatus(),
                chapter.getGmtModified());
    }

    private ChapterContent chapterContent(ChapterEntity chapter) {
        return new ChapterContent(
                chapter.getWorkId(),
                chapter.getId(),
                chapter.getTitle(),
                chapter.getContent(),
                chapter.getVersion(),
                wordCount(chapter.getContent()),
                chapter.getGmtModified());
    }

    private BusinessException versionConflict(ChapterEntity serverChapter) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverContent", serverChapter.getContent());
        data.put("version", serverChapter.getVersion());
        data.put("serverSavedAt", serverChapter.getGmtModified());
        return new BusinessException(
                ErrorCode.CHAPTER_VERSION_CONFLICT,
                "章节正文已被更新，请刷新后重试",
                data);
    }

    private void requirePreview(ChapterGenerationEntity generation) {
        if (!STATUS_PREVIEW.equals(generation.getGenerationStatus())) {
            throw statusConflict();
        }
    }

    private BusinessException statusConflict() {
        return new BusinessException(
                ErrorCode.GENERATION_STATUS_CONFLICT,
                "当前生成状态不允许执行该操作");
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private String requiredAllowed(String value, Set<String> allowed, String field) {
        String normalized = optionalText(value);
        if (!StringUtils.hasText(normalized) || !allowed.contains(normalized)) {
            throw badRequest(field + " 取值非法");
        }
        return normalized;
    }

    private String optionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String applyContent(String current, String generated, String applyMode) {
        String generatedContent = generated == null ? "" : generated;
        if (REPLACE_APPLY_MODE.equals(applyMode) || !StringUtils.hasText(current)) {
            return generatedContent;
        }
        return current + "\n\n" + generatedContent;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String snapshotText(Object value, String defaultValue) {
        String snapshotValue = stringValue(value);
        return StringUtils.hasText(snapshotValue) ? snapshotValue : defaultValue;
    }

    private String number(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private String json(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            appendJsonCharacter(escaped, value.charAt(index));
        }
        return escaped.toString();
    }

    private void appendJsonCharacter(StringBuilder escaped, char character) {
        if (character == JSON_QUOTATION_MARK) {
            escaped.append("\\\"");
            return;
        }
        if (character == JSON_REVERSE_SOLIDUS) {
            escaped.append("\\\\");
            return;
        }
        if (character == JSON_BACKSPACE) {
            escaped.append("\\b");
            return;
        }
        if (character == JSON_FORM_FEED) {
            escaped.append("\\f");
            return;
        }
        if (character == JSON_LINE_FEED) {
            escaped.append("\\n");
            return;
        }
        if (character == JSON_CARRIAGE_RETURN) {
            escaped.append("\\r");
            return;
        }
        if (character == JSON_TAB) {
            escaped.append("\\t");
            return;
        }
        if (character >= JSON_CONTROL_CHARACTER_LIMIT) {
            escaped.append(character);
            return;
        }
        String hexadecimal = Integer.toHexString(character);
        escaped.append("\\u");
        escaped.append("0".repeat(4 - hexadecimal.length()));
        escaped.append(hexadecimal);
    }

    private BusinessException invalidCustomWordCount() {
        return badRequest("customWordCount 必须在 1 到 100000 之间");
    }

    private int wordCount(String value) {
        if (value == null) {
            return 0;
        }
        return (int) value.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();
    }

    private record GenerationOptions(
            String generationMode,
            String lengthPreset,
            Integer customWordCount) {
    }
}
