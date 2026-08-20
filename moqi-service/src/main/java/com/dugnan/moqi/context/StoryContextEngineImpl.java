package com.dugnan.moqi.context;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.context.entity.StoryContextSnapshotEntity;
import com.dugnan.moqi.context.mapper.StoryContextSnapshotMapper;
import com.dugnan.moqi.knowledge.entity.ChapterKeyEventEntity;
import com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity;
import com.dugnan.moqi.knowledge.entity.ForeshadowingItemEntity;
import com.dugnan.moqi.knowledge.entity.SettingEntryEntity;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 确定性组装带权威状态的故事上下文并持久化版本化快照。
 */
@Service
public class StoryContextEngineImpl implements StoryContextEngine, StoryContextSnapshotQueryPort {

    private static final int SETTING_LIMIT = 100;
    private static final int FORESHADOWING_LIMIT = 100;
    private static final int SUMMARY_LIMIT = 50;
    private static final int KEY_EVENT_LIMIT = 200;
    private static final int MESSAGE_LIMIT = 100;
    private static final int MAX_VERSION_INSERT_RETRIES = 3;
    private static final String DECISIONS_FIELD = "decisions";
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_CANDIDATE = "candidate";
    private static final String STATUS_REJECTED = "rejected";
    private static final String SYSTEM_RULE =
            "你是墨契的章节共创助手。请严格遵循已确认的故事设定，围绕当前任务给出清晰、可执行的创作建议。";

    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterBriefMapper briefMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final SettingEntryMapper settingMapper;
    private final ForeshadowingItemMapper foreshadowingMapper;
    private final ChapterSummaryMapper summaryMapper;
    private final ChapterKeyEventMapper eventMapper;
    private final ChapterConversationMapper conversationMapper;
    private final ChapterConversationMessageMapper messageMapper;
    private final StoryContextSnapshotMapper snapshotMapper;
    private final TokenEstimator tokenEstimator;
    private final ObjectMapper objectMapper;

    /**
     * 创建故事上下文引擎。
     *
     * @param workMapper 作品数据访问对象
     * @param chapterMapper 章节数据访问对象
     * @param briefMapper Brief 数据访问对象
     * @param outlineMapper 大纲数据访问对象
     * @param settingMapper 正式设定数据访问对象
     * @param foreshadowingMapper 伏笔数据访问对象
     * @param summaryMapper 章节摘要数据访问对象
     * @param eventMapper 章节关键事件数据访问对象
     * @param conversationMapper 会话数据访问对象
     * @param messageMapper 会话消息数据访问对象
     * @param snapshotMapper 上下文快照数据访问对象
     * @param tokenEstimator token 估算器
     * @param objectMapper JSON 映射器
     */
    public StoryContextEngineImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterBriefMapper briefMapper,
            ChapterOutlineQueryMapper outlineMapper,
            SettingEntryMapper settingMapper,
            ForeshadowingItemMapper foreshadowingMapper,
            ChapterSummaryMapper summaryMapper,
            ChapterKeyEventMapper eventMapper,
            ChapterConversationMapper conversationMapper,
            ChapterConversationMessageMapper messageMapper,
            StoryContextSnapshotMapper snapshotMapper,
            TokenEstimator tokenEstimator,
            ObjectMapper objectMapper) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.briefMapper = briefMapper;
        this.outlineMapper = outlineMapper;
        this.settingMapper = settingMapper;
        this.foreshadowingMapper = foreshadowingMapper;
        this.summaryMapper = summaryMapper;
        this.eventMapper = eventMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.snapshotMapper = snapshotMapper;
        this.tokenEstimator = tokenEstimator;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public StoryContextSnapshot build(StoryContextBuildCommand command) {
        validateBudget(command);
        WorkEntity work = requireWork(command.workId());
        ChapterEntity chapter = command.chapterId() == null ? null : requireChapter(command.chapterId());
        validateScope(command, chapter);
        ChapterConversationEntity conversation = command.conversationId() == null
                ? null : requireConversation(command.conversationId());
        validateConversationScope(conversation, command, chapter);
        validateCurrentMessage(command, conversation, chapter);

        List<Candidate> candidates = collectCandidates(command, work, chapter, conversation);
        Selection selection = select(command, candidates);
        String canonical = canonicalContent(command, selection);
        String hash = sha256(canonical);
        String scopeKey = scopeKey(command);
        StoryContextSnapshotEntity existing = snapshotMapper.selectOne(new LambdaQueryWrapper<StoryContextSnapshotEntity>()
                .eq(StoryContextSnapshotEntity::getScopeKey, scopeKey)
                .eq(StoryContextSnapshotEntity::getContentHash, hash)
                .eq(StoryContextSnapshotEntity::getDeleted, 0));
        if (existing != null) {
            return snapshot(existing);
        }
        long version = nextVersion(scopeKey);
        StoryContextSnapshotEntity entity = new StoryContextSnapshotEntity();
        entity.setScopeKey(scopeKey);
        entity.setWorkId(command.workId());
        entity.setChapterId(command.chapterId());
        entity.setConversationId(command.conversationId());
        entity.setProfile(command.profile().name());
        entity.setSchemaVersion(2);
        entity.setSnapshotVersion(version);
        entity.setContextWindowTokens(command.contextWindowTokens());
        entity.setOutputReserveTokens(command.outputReserveTokens());
        entity.setInputBudgetTokens(command.inputBudgetTokens());
        entity.setEstimatedInputTokens(selection.estimatedTokens());
        entity.setContentHash(hash);
        entity.setSnapshotJson(snapshotJson(selection));
        entity.setDeleted(0);
        entity.setVersion(0);
        for (int attempt = 0; attempt < MAX_VERSION_INSERT_RETRIES; attempt++) {
            try {
                snapshotMapper.insert(entity);
                return new StoryContextSnapshot(
                        entity.getId(), scopeKey, command.workId(), command.chapterId(), command.conversationId(),
                        command.profile(), 2, entity.getSnapshotVersion(), command.contextWindowTokens(),
                        command.outputReserveTokens(), command.inputBudgetTokens(), selection.estimatedTokens(), hash,
                        selection.items(), selection.decisions(), entity.getGmtCreate());
            } catch (DuplicateKeyException exception) {
                StoryContextSnapshotEntity concurrent = snapshotMapper.selectOne(
                        new LambdaQueryWrapper<StoryContextSnapshotEntity>()
                                .eq(StoryContextSnapshotEntity::getScopeKey, scopeKey)
                                .eq(StoryContextSnapshotEntity::getContentHash, hash)
                                .eq(StoryContextSnapshotEntity::getDeleted, 0));
                if (concurrent != null) {
                    return snapshot(concurrent);
                }
                entity.setSnapshotVersion(nextVersion(scopeKey));
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "上下文快照版本并发写入失败");
    }

    @Override
    public StoryContextSnapshot load(Long snapshotId) {
        StoryContextSnapshotEntity entity = snapshotId == null ? null : snapshotMapper.selectById(snapshotId);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.STORY_CONTEXT_SNAPSHOT_NOT_FOUND, "故事上下文快照不存在");
        }
        return snapshot(entity);
    }

    private List<Candidate> collectCandidates(
            StoryContextBuildCommand command,
            WorkEntity work,
            ChapterEntity chapter,
            ChapterConversationEntity conversation) {
        List<Candidate> candidates = new ArrayList<>();
        add(candidates, StoryContextSourceType.SYSTEM_RULE, "system-v1", "SYSTEM", SYSTEM_RULE,
                true, 1000, 0, null, null, Category.STRUCTURE);
        if (StringUtils.hasText(command.taskInstruction())) {
            add(candidates, StoryContextSourceType.TASK_RULE, "task", "SYSTEM", command.taskInstruction(),
                    true, 990, 1, null, null, Category.STRUCTURE);
        }
        boolean sceneGeneration = command.sceneGenerationFocus() != null;
        if (!sceneGeneration) {
            add(candidates, StoryContextSourceType.WORK_METADATA, id(work.getId()), "SYSTEM",
                    "作品：" + work.getTitle(), false, 900, 10, work.getVersion(), work.getGmtModified(), Category.STRUCTURE);
        }
        addDiscussionFocus(candidates, command);
        addSceneGenerationFocus(candidates, command);
        if (chapter != null && !sceneGeneration) {
            if (command.discussionFocus() == null) {
                addBrief(candidates, command, chapter);
            }
            addOutline(candidates, command, chapter);
            if (StringUtils.hasText(chapter.getContent())) {
                add(candidates, StoryContextSourceType.CHAPTER_CONTENT, id(chapter.getId()), "SYSTEM",
                        chapter.getContent(), false, 700, 300, chapter.getVersion(), chapter.getGmtModified(), Category.CURRENT);
            }
        }
        if (!sceneGeneration) {
            addKnowledge(candidates, command, chapter);
        }
        if (StringUtils.hasText(command.targetText())) {
            add(candidates, StoryContextSourceType.TARGET_TEXT, "target", "SYSTEM", command.targetText(),
                    command.profile() != StoryContextProfile.CHAPTER_DISCUSSION, 850, 310, null, null, Category.CURRENT);
        }
        addConversationHistory(candidates, command, conversation);
        if (command.messageReference() != null) {
            MessageReference reference = command.messageReference();
            add(candidates, StoryContextSourceType.TARGET_TEXT, "referenced-" + reference.messageId(),
                    reference.role().toUpperCase(), "[referencedMessageId=" + reference.messageId() + "]\n" + reference.content(),
                    true, 995, 490, null, null, Category.CURRENT);
        }
        if (StringUtils.hasText(command.currentInput())) {
            add(candidates, StoryContextSourceType.USER_INPUT, id(command.currentMessageId()), "USER",
                    command.currentInput(), true, 1000, 500, null, null, Category.CURRENT);
        }
        return candidates;
    }

    /**
     * 将服务端解析的对焦资料按稳定顺序加入候选上下文。
     *
     * @param candidates 候选上下文
     * @param command 构建命令
     */
    private void addDiscussionFocus(
            List<Candidate> candidates,
            StoryContextBuildCommand command) {
        StoryContextFocus focus = command.discussionFocus();
        if (focus == null) {
            return;
        }
        add(
                candidates,
                StoryContextSourceType.DECISION_FOCUS,
                focus.briefId() + ":" + focus.decisionKey(),
                "SYSTEM",
                focus.decisionContent(),
                true,
                1000,
                20,
                focus.briefVersion(),
                null,
                Category.STRUCTURE,
                focusAuthority(focus.decisionStatus()));
        add(
                candidates,
                StoryContextSourceType.CHAPTER_CONSENSUS,
                id(focus.briefId()),
                "SYSTEM",
                confirmedConsensusContent(focus.consensusContent()),
                true,
                990,
                30,
                focus.briefVersion(),
                null,
                Category.STRUCTURE,
                StoryContextAuthorityStatus.CONFIRMED);
        addRejectedDecisionTombstones(
                candidates,
                focus.consensusContent(),
                id(focus.briefId()),
                focus.briefVersion(),
                31);
        int order = 40;
        for (StoryContextFocus.StoryContextFocusSource source : focus.sources()) {
            add(
                    candidates,
                    StoryContextSourceType.DECISION_SOURCE_MESSAGE,
                    id(source.messageId()),
                    "SYSTEM",
                    source.messageRole() + "：" + source.content(),
                    false,
                    980,
                    order,
                    null,
                    null,
                    Category.HISTORY);
            order++;
        }
    }

    private void addSceneGenerationFocus(
            List<Candidate> candidates,
            StoryContextBuildCommand command) {
        SceneGenerationContextFocus focus = command.sceneGenerationFocus();
        if (focus == null) {
            return;
        }
        add(candidates, StoryContextSourceType.CHAPTER_GENERATION_BRIEF, focus.briefFingerprint(), "SYSTEM",
                focus.generationBriefContent(), true, 996, 24, focus.briefTemplateVersion(), null,
                Category.STRUCTURE);
        if (focus.immediatePreviousScene() != null) {
            SceneGenerationContextFocus.PreviousSceneDraft previous = focus.immediatePreviousScene();
            add(candidates, StoryContextSourceType.GENERATED_SCENE_DRAFT, id(previous.generationSceneId()), "SYSTEM",
                    "紧邻上一场完整正文（必须从最后动作续写）：\n" + previous.content(), true, 993, 27,
                    null, null, Category.CURRENT);
        }
        int order = 320;
        for (SceneGenerationContextFocus.PreviousSceneDraft previous : focus.previousScenes()) {
            add(candidates, StoryContextSourceType.GENERATED_SCENE_DRAFT, id(previous.generationSceneId()), "SYSTEM",
                    "前序场景（" + previous.sceneKey() + "）：\n" + previous.content(), false, 840, order,
                    null, null, Category.CURRENT);
            order++;
        }
    }

    private void addBrief(List<Candidate> candidates, StoryContextBuildCommand command, ChapterEntity chapter) {
        List<ChapterBriefEntity> briefs = briefMapper.selectList(new LambdaQueryWrapper<ChapterBriefEntity>()
                .eq(ChapterBriefEntity::getChapterId, chapter.getId())
                .eq(ChapterBriefEntity::getDeleted, 0)
                .eq(ChapterBriefEntity::getBriefStatus, "confirmed")
                .orderByDesc(ChapterBriefEntity::getGmtModified)
                .orderByDesc(ChapterBriefEntity::getId)
                .last("LIMIT 1"));
        if (!briefs.isEmpty()) {
            ChapterBriefEntity brief = briefs.get(0);
            add(candidates, StoryContextSourceType.CHAPTER_BRIEF, id(brief.getId()), "SYSTEM",
                    confirmedConsensusContent(brief.getBriefContent()),
                    false, 900, 100,
                    brief.getVersion(), brief.getGmtModified(), Category.STRUCTURE);
            addRejectedDecisionTombstones(
                    candidates,
                    brief.getBriefContent(),
                    id(brief.getId()),
                    brief.getVersion(),
                    101);
        }
        if (command.profile() == StoryContextProfile.CHAPTER_DISCUSSION) {
            List<ChapterBriefEntity> rejected = briefMapper.selectList(new LambdaQueryWrapper<ChapterBriefEntity>()
                    .eq(ChapterBriefEntity::getChapterId, chapter.getId())
                    .eq(ChapterBriefEntity::getBriefStatus, "rejected")
                    .eq(ChapterBriefEntity::getDeleted, 0)
                    .orderByDesc(ChapterBriefEntity::getGmtModified)
                    .orderByDesc(ChapterBriefEntity::getId)
                    .last("LIMIT 1"));
            if (!rejected.isEmpty()) {
                ChapterBriefEntity brief = rejected.get(0);
                add(candidates, StoryContextSourceType.CHAPTER_BRIEF, id(brief.getId()), "SYSTEM",
                        "已否定 Brief #" + brief.getId() + "，不得继承其内容。", false, 880, 105,
                        brief.getVersion(), brief.getGmtModified(), Category.STRUCTURE,
                        StoryContextAuthorityStatus.REJECTED);
            }
        }
    }

    private void addOutline(List<Candidate> candidates, StoryContextBuildCommand command, ChapterEntity chapter) {
        List<ChapterOutlineEntity> outlines = outlineMapper.selectList(new LambdaQueryWrapper<ChapterOutlineEntity>()
                .eq(ChapterOutlineEntity::getChapterId, chapter.getId())
                .eq(ChapterOutlineEntity::getDeleted, 0)
                .eq(ChapterOutlineEntity::getOutlineStatus, "confirmed")
                .orderByDesc(ChapterOutlineEntity::getRevision)
                .orderByDesc(ChapterOutlineEntity::getId)
                .last("LIMIT 1"));
        if (!outlines.isEmpty()) {
            ChapterOutlineEntity outline = outlines.get(0);
            add(candidates, StoryContextSourceType.CHAPTER_OUTLINE, id(outline.getId()), "SYSTEM",
                    outline.getOutlineContent(), false, "confirmed".equals(outline.getOutlineStatus()) ? 900 : 650, 110,
                    outline.getVersion() + ":" + outline.getRevision(), outline.getGmtModified(), Category.STRUCTURE);
        }
    }

    private void addKnowledge(List<Candidate> candidates, StoryContextBuildCommand command, ChapterEntity chapter) {
        settingMapper.selectList(new LambdaQueryWrapper<SettingEntryEntity>()
                        .eq(SettingEntryEntity::getWorkId, command.workId())
                        .eq(SettingEntryEntity::getEntryStatus, "active")
                        .eq(SettingEntryEntity::getDeleted, 0)
                        .orderByDesc(SettingEntryEntity::getGmtModified)
                        .orderByDesc(SettingEntryEntity::getId)
                        .last("LIMIT " + SETTING_LIMIT))
                .forEach(setting -> add(candidates, StoryContextSourceType.SETTING_ENTRY, id(setting.getId()), "SYSTEM",
                        setting.getName() + "：" + setting.getContent(), false, 800, 200,
                        setting.getVersion(), setting.getGmtModified(), Category.KNOWLEDGE));
        foreshadowingMapper.selectList(new LambdaQueryWrapper<ForeshadowingItemEntity>()
                        .eq(ForeshadowingItemEntity::getWorkId, command.workId())
                        .ne(ForeshadowingItemEntity::getStatus, "abandoned")
                        .eq(ForeshadowingItemEntity::getDeleted, 0)
                        .orderByDesc(ForeshadowingItemEntity::getGmtModified)
                        .orderByDesc(ForeshadowingItemEntity::getId)
                        .last("LIMIT " + FORESHADOWING_LIMIT))
                .forEach(item -> add(candidates, StoryContextSourceType.FORESHADOWING, id(item.getId()), "SYSTEM",
                        item.getTitle() + "：" + item.getDescription(), false, 780, 220,
                        item.getVersion(), item.getGmtModified(), Category.KNOWLEDGE));
        summaryMapper.selectList(new LambdaQueryWrapper<ChapterSummaryEntity>()
                        .eq(ChapterSummaryEntity::getWorkId, command.workId())
                        .eq(ChapterSummaryEntity::getSummaryStatus, "confirmed")
                        .eq(ChapterSummaryEntity::getDeleted, 0)
                        .orderByDesc(ChapterSummaryEntity::getGmtModified)
                        .orderByDesc(ChapterSummaryEntity::getId)
                        .last("LIMIT " + SUMMARY_LIMIT))
                .forEach(summary -> add(candidates, StoryContextSourceType.CHAPTER_SUMMARY, id(summary.getId()), "SYSTEM",
                        "章节摘要：" + summary.getSummary(), false, 760, 230,
                        summary.getVersion() + ":" + summary.getContentRevision(), summary.getGmtModified(), Category.KNOWLEDGE));
        eventMapper.selectList(new LambdaQueryWrapper<ChapterKeyEventEntity>()
                        .eq(ChapterKeyEventEntity::getWorkId, command.workId())
                        .eq(ChapterKeyEventEntity::getDeleted, 0)
                        .orderByDesc(ChapterKeyEventEntity::getGmtModified)
                        .orderByDesc(ChapterKeyEventEntity::getId)
                        .last("LIMIT " + KEY_EVENT_LIMIT))
                .forEach(event -> add(candidates, StoryContextSourceType.CHAPTER_KEY_EVENT, id(event.getId()), "SYSTEM",
                        event.getEventTitle() + "：" + event.getEventContent(), false, 700, 240,
                        event.getVersion(), event.getGmtModified(), Category.KNOWLEDGE));
    }

    private void addConversationHistory(
            List<Candidate> candidates,
            StoryContextBuildCommand command,
            ChapterConversationEntity conversation) {
        if (conversation == null) {
            return;
        }
        List<ChapterConversationMessageEntity> messages = new ArrayList<>(messageMapper.selectList(
                new LambdaQueryWrapper<ChapterConversationMessageEntity>()
                        .eq(ChapterConversationMessageEntity::getConversationId, conversation.getId())
                        .eq(ChapterConversationMessageEntity::getDeleted, 0)
                        .lt(command.currentMessageId() != null, ChapterConversationMessageEntity::getId, command.currentMessageId())
                        .orderByDesc(ChapterConversationMessageEntity::getId)
                        .last("LIMIT " + MESSAGE_LIMIT)));
        messages.sort(Comparator.comparing(ChapterConversationMessageEntity::getId));
        int historyOrder = command.discussionFocus() == null ? 400 : 60;
        for (int index = 0; index + 1 < messages.size(); index++) {
            ChapterConversationMessageEntity user = messages.get(index);
            ChapterConversationMessageEntity assistant = messages.get(index + 1);
            if (!"user".equals(user.getMessageRole()) || !"assistant".equals(assistant.getMessageRole())) {
                continue;
            }
            add(candidates, StoryContextSourceType.CONVERSATION_TURN,
                    user.getId() + ":" + assistant.getId(), "USER",
                    "用户：" + user.getContent() + "\n助手：" + conversationAssistantContent(assistant), false,
                    600, historyOrder, user.getVersion() + ":" + assistant.getVersion(),
                    assistant.getGmtModified(), Category.HISTORY);
            historyOrder++;
            index++;
        }
    }

    private String conversationAssistantContent(ChapterConversationMessageEntity message) {
        return "stopped".equals(message.getGenerationStatus())
                ? "[不完整：作者已停止本次生成，不得据此确认共识或写入权威内容]\n" + message.getContent()
                : message.getContent();
    }

    private Selection select(StoryContextBuildCommand command, List<Candidate> candidates) {
        Map<String, Candidate> sourceDeduplicated = candidates.stream()
                .collect(Collectors.toMap(Candidate::dedupeKey, Function.identity(), this::prefer, LinkedHashMap::new));
        List<Candidate> unique = new ArrayList<>(sourceDeduplicated.values());
        Set<String> contentKeys = new HashSet<>();
        List<Candidate> deduplicated = new ArrayList<>();
        List<StoryContextSelectionDecision> decisions = new ArrayList<>();
        for (Candidate candidate : unique) {
            String contentKey = normalize(candidate.content());
            if (!contentKeys.add(contentKey)) {
                decisions.add(decision(candidate, "DUPLICATE_CONTENT"));
            } else {
                deduplicated.add(candidate);
            }
        }
        int budget = command.inputBudgetTokens();
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : deduplicated.stream().filter(Candidate::required).sorted(byOrder()).toList()) {
            Candidate fitted = fitRequired(candidate, budget);
            if (fitted == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "必需上下文超过模型输入预算");
            }
            selected.add(fitted);
            budget -= fitted.selectedTokens();
        }
        List<Candidate> optional = deduplicated.stream().filter(candidate -> !candidate.required()).toList();
        Map<Category, Integer> quotas = quotas(command.profile(), Math.max(0, budget));
        for (Category category : Category.values()) {
            int quota = quotas.get(category);
            for (Candidate candidate : optional.stream().filter(item -> item.category() == category)
                    .sorted(byPriority()).toList()) {
                Candidate fitted = fitOptional(candidate, Math.min(quota, budget));
                if (fitted != null) {
                    selected.add(fitted);
                    quota -= fitted.selectedTokens();
                    budget -= fitted.selectedTokens();
                } else {
                    decisions.add(decision(candidate, "PARTITION_BUDGET"));
                }
            }
        }
        for (Candidate candidate : optional.stream().filter(item -> !selected.contains(item))
                .sorted(byPriority()).toList()) {
            if (candidate.tokens() <= budget) {
                selected.add(candidate);
                budget -= candidate.tokens();
            } else {
                decisions.add(decision(candidate, "INPUT_BUDGET"));
            }
        }
        selected.sort(byOrder());
        List<StoryContextItem> items = selected.stream().map(Candidate::item).toList();
        return new Selection(items, decisions, items.stream().mapToInt(StoryContextItem::selectedTokenEstimate).sum());
    }

    private Candidate fitOptional(Candidate candidate, int budget) {
        if (candidate.tokens() <= budget) {
            return candidate;
        }
        if (budget <= 0 || !isTruncatable(candidate.sourceType())) {
            return null;
        }
        String truncated = tokenEstimator.truncate(candidate.content(), budget);
        int selectedTokens = tokenEstimator.estimate(truncated);
        return StringUtils.hasText(truncated) && selectedTokens <= budget
                ? candidate.withContent(truncated, selectedTokens, "TRUNCATED_TO_PARTITION") : null;
    }

    private boolean isTruncatable(StoryContextSourceType sourceType) {
        return sourceType == StoryContextSourceType.CHAPTER_CONTENT
                || sourceType == StoryContextSourceType.CHAPTER_BRIEF
                || sourceType == StoryContextSourceType.CHAPTER_OUTLINE
                || sourceType == StoryContextSourceType.TARGET_TEXT
                || sourceType == StoryContextSourceType.GENERATED_SCENE_DRAFT
                || sourceType == StoryContextSourceType.DECISION_SOURCE_MESSAGE;
    }

    private Candidate fitRequired(Candidate candidate, int budget) {
        if (candidate.tokens() <= budget) {
            return candidate;
        }
        if (candidate.sourceType() == StoryContextSourceType.USER_INPUT
                || candidate.sourceType() == StoryContextSourceType.SYSTEM_RULE
                || candidate.sourceType() == StoryContextSourceType.TASK_RULE) {
            return null;
        }
        String truncated = tokenEstimator.truncate(candidate.content(), budget);
        if (!StringUtils.hasText(truncated) || tokenEstimator.estimate(truncated) > budget) {
            return null;
        }
        return candidate.withContent(truncated, tokenEstimator.estimate(truncated), "TRUNCATED_TO_FIT");
    }

    private Map<Category, Integer> quotas(StoryContextProfile profile, int budget) {
        Map<Category, Integer> result = new LinkedHashMap<>();
        result.put(Category.STRUCTURE, budget * profile.structurePercent() / 100);
        result.put(Category.KNOWLEDGE, budget * profile.knowledgePercent() / 100);
        result.put(Category.CURRENT, budget * profile.currentTextPercent() / 100);
        result.put(Category.HISTORY, budget * profile.historyPercent() / 100);
        return result;
    }

    private void add(
            List<Candidate> candidates,
            StoryContextSourceType sourceType,
            String sourceId,
            String role,
            String content,
            boolean required,
            int priority,
            int order,
            Object contentVersion,
            LocalDateTime updatedAt,
            Category category) {
        add(candidates, sourceType, sourceId, role, content, required, priority, order,
                contentVersion, updatedAt, category, authorityStatus(sourceType));
    }

    private void add(
            List<Candidate> candidates,
            StoryContextSourceType sourceType,
            String sourceId,
            String role,
            String content,
            boolean required,
            int priority,
            int order,
            Object contentVersion,
            LocalDateTime updatedAt,
            Category category,
            StoryContextAuthorityStatus authorityStatus) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        String labeledContent = labelAuthority(content, authorityStatus);
        candidates.add(new Candidate(sourceType, sourceId, contentVersion == null ? null : String.valueOf(contentVersion),
                updatedAt, role, labeledContent, required, priority, order, tokenEstimator.estimate(labeledContent),
                tokenEstimator.estimate(labeledContent), "INCLUDED", category, authorityStatus));
    }

    private StoryContextAuthorityStatus authorityStatus(StoryContextSourceType sourceType) {
        return switch (sourceType) {
            case SYSTEM_RULE, TASK_RULE, WORK_METADATA, CHAPTER_BRIEF, CHAPTER_OUTLINE,
                    CHAPTER_GENERATION_BRIEF,
                    SETTING_ENTRY, FORESHADOWING, CHAPTER_SUMMARY, CHAPTER_KEY_EVENT, SCENE_PLAN ->
                    StoryContextAuthorityStatus.CONFIRMED;
            case DECISION_FOCUS -> StoryContextAuthorityStatus.PENDING;
            case CHAPTER_CONSENSUS -> StoryContextAuthorityStatus.CANDIDATE;
            default -> StoryContextAuthorityStatus.EVIDENCE;
        };
    }

    private StoryContextAuthorityStatus focusAuthority(String decisionStatus) {
        if (STATUS_REJECTED.equals(decisionStatus)) {
            return StoryContextAuthorityStatus.REJECTED;
        }
        if (STATUS_CANDIDATE.equals(decisionStatus)) {
            return StoryContextAuthorityStatus.CANDIDATE;
        }
        return StoryContextAuthorityStatus.PENDING;
    }

    private String labelAuthority(String content, StoryContextAuthorityStatus authorityStatus) {
        if (authorityStatus == StoryContextAuthorityStatus.EVIDENCE) {
            return "【权威状态：evidence（证据）】" + content;
        }
        return "【权威状态：" + authorityStatus.value() + "】" + content;
    }

    private String confirmedConsensusContent(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            if (!(root instanceof ObjectNode object)
                    || !(object.get(DECISIONS_FIELD) instanceof ArrayNode decisions)) {
                return content;
            }
            ArrayNode confirmed = objectMapper.createArrayNode();
            for (JsonNode decision : decisions) {
                if (STATUS_CONFIRMED.equals(decision.path("status").asText())) {
                    confirmed.add(decision);
                }
            }
            ObjectNode copy = object.deepCopy();
            copy.set(DECISIONS_FIELD, confirmed);
            return objectMapper.writeValueAsString(copy);
        } catch (JsonProcessingException exception) {
            return content;
        }
    }

    private void addRejectedDecisionTombstones(
            List<Candidate> candidates,
            String content,
            String briefId,
            Object contentVersion,
            int firstOrder) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        try {
            JsonNode decisions = objectMapper.readTree(content).path(DECISIONS_FIELD);
            if (!decisions.isArray()) {
                return;
            }
            int order = firstOrder;
            for (JsonNode decision : decisions) {
                if (!STATUS_REJECTED.equals(decision.path("status").asText())) {
                    continue;
                }
                String key = decision.path("key").asText("unknown");
                String title = decision.path("title").asText(key);
                add(candidates, StoryContextSourceType.CHAPTER_CONSENSUS,
                        briefId + ":rejected:" + key, "SYSTEM",
                        "已否定决定：" + title + "（" + key + "），不得继承候选内容。",
                        false, 970, order, contentVersion, null, Category.STRUCTURE,
                        StoryContextAuthorityStatus.REJECTED);
                order++;
            }
        } catch (JsonProcessingException exception) {
            // 历史自由文本 Brief 没有结构化决定，不伪造拒绝状态。
        }
    }

    private Candidate prefer(Candidate first, Candidate second) {
        return first.priority() >= second.priority() ? first : second;
    }

    private String canonicalContent(StoryContextBuildCommand command, Selection selection) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("profile", command.profile().name());
        canonical.put("contextWindowTokens", command.contextWindowTokens());
        canonical.put("outputReserveTokens", command.outputReserveTokens());
        canonical.put("items", selection.items());
        canonical.put("decisions", selection.decisions());
        try {
            return objectMapper.writeValueAsString(canonical);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "上下文快照序列化失败", exception);
        }
    }

    private String snapshotJson(Selection selection) {
        Map<String, Object> payload = Map.of("items", selection.items(), "decisions", selection.decisions());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "上下文快照序列化失败", exception);
        }
    }

    private long nextVersion(String scopeKey) {
        StoryContextSnapshotEntity latest = snapshotMapper.selectOne(new LambdaQueryWrapper<StoryContextSnapshotEntity>()
                .eq(StoryContextSnapshotEntity::getScopeKey, scopeKey)
                .eq(StoryContextSnapshotEntity::getDeleted, 0)
                .orderByDesc(StoryContextSnapshotEntity::getSnapshotVersion)
                .last("LIMIT 1"));
        return latest == null || latest.getSnapshotVersion() == null ? 1 : latest.getSnapshotVersion() + 1;
    }

    private StoryContextSnapshot snapshot(StoryContextSnapshotEntity entity) {
        try {
            SnapshotPayload payload = objectMapper.readValue(entity.getSnapshotJson(), SnapshotPayload.class);
            return new StoryContextSnapshot(entity.getId(), entity.getScopeKey(), entity.getWorkId(), entity.getChapterId(),
                    entity.getConversationId(), StoryContextProfile.valueOf(entity.getProfile()), entity.getSchemaVersion(),
                    entity.getSnapshotVersion(), entity.getContextWindowTokens(), entity.getOutputReserveTokens(),
                    entity.getInputBudgetTokens(), entity.getEstimatedInputTokens(), entity.getContentHash(),
                    payload.items(), payload.decisions(), entity.getGmtCreate());
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "上下文快照读取失败", exception);
        }
    }

    private void validateBudget(StoryContextBuildCommand command) {
        if (command.inputBudgetTokens() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型没有可用的上下文输入预算");
        }
    }

    private WorkEntity requireWork(Long workId) {
        WorkEntity work = workMapper.selectById(workId);
        if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return work;
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private ChapterConversationEntity requireConversation(Long conversationId) {
        ChapterConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || Integer.valueOf(1).equals(conversation.getDeleted())) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    private void validateScope(StoryContextBuildCommand command, ChapterEntity chapter) {
        if (chapter != null && !command.workId().equals(chapter.getWorkId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "章节不属于当前作品");
        }
    }

    private void validateCurrentMessage(
            StoryContextBuildCommand command,
            ChapterConversationEntity conversation,
            ChapterEntity chapter) {
        if (!StringUtils.hasText(command.currentInput())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前用户输入不能为空");
        }
        if (command.currentMessageId() == null) {
            return;
        }
        ChapterConversationMessageEntity message = messageMapper.selectById(command.currentMessageId());
        if (!belongsToCurrentScope(message, conversation, chapter)
                || !normalize(command.currentInput()).equals(normalize(message.getContent()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前消息不属于上下文作用域");
        }
    }

    private boolean belongsToCurrentScope(
            ChapterConversationMessageEntity message,
            ChapterConversationEntity conversation,
            ChapterEntity chapter) {
        if (message == null || Integer.valueOf(1).equals(message.getDeleted())
                || !"user".equals(message.getMessageRole()) || conversation == null) {
            return false;
        }
        if (!conversation.getId().equals(message.getConversationId())) {
            return false;
        }
        return chapter == null || chapter.getId().equals(message.getChapterId());
    }

    private void validateConversationScope(
            ChapterConversationEntity conversation,
            StoryContextBuildCommand command,
            ChapterEntity chapter) {
        if (conversation == null) {
            return;
        }
        if (!command.workId().equals(conversation.getWorkId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话不属于当前作品或章节");
        }
        if (chapter != null && !chapter.getId().equals(conversation.getChapterId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话不属于当前作品或章节");
        }
    }

    private String scopeKey(StoryContextBuildCommand command) {
        return command.profile().name().toLowerCase(Locale.ROOT) + ":" + command.workId()
                + ":" + String.valueOf(command.chapterId()) + ":" + String.valueOf(command.conversationId());
    }

    private String normalize(String content) {
        return Normalizer.normalize(content, Normalizer.Form.NFKC)
                .replace("\r\n", "\n").trim().replaceAll("\\s+", " ");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "上下文快照哈希算法不可用", exception);
        }
    }

    private String id(Long value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    private StoryContextSelectionDecision decision(Candidate candidate, String reason) {
        return new StoryContextSelectionDecision(candidate.sourceType(), candidate.sourceId(), candidate.tokens(), reason);
    }

    private Comparator<Candidate> byOrder() {
        return Comparator.comparingInt(Candidate::order).thenComparing(Candidate::sourceId);
    }

    private Comparator<Candidate> byPriority() {
        return Comparator.comparingInt(Candidate::priority).reversed()
                .thenComparingInt(Candidate::order).thenComparing(Candidate::sourceId);
    }

    private enum Category {
        /** 作品和章节结构。 */
        STRUCTURE,
        /** 正式知识层。 */
        KNOWLEDGE,
        /** 当前正文或目标。 */
        CURRENT,
        /** 历史对话。 */
        HISTORY
    }

    private record Candidate(
            StoryContextSourceType sourceType,
            String sourceId,
            String contentVersion,
            LocalDateTime updatedAt,
            String role,
            String content,
            boolean required,
            int priority,
            int order,
            int tokens,
            int selectedTokens,
            String reason,
            Category category,
            StoryContextAuthorityStatus authorityStatus) {

        private Candidate withContent(String value, int selectedTokenCount, String selectionReason) {
            return new Candidate(sourceType, sourceId, contentVersion, updatedAt, role, value, required,
                    priority, order, tokens, selectedTokenCount, selectionReason, category, authorityStatus);
        }

        private String dedupeKey() {
            return sourceType + ":" + sourceId + ":" + contentVersion;
        }

        private StoryContextItem item() {
            return new StoryContextItem(sourceType, sourceId, contentVersion, updatedAt, role, content, required,
                    priority, order, tokens, selectedTokens, reason, authorityStatus);
        }
    }

    private record Selection(
            List<StoryContextItem> items,
            List<StoryContextSelectionDecision> decisions,
            int estimatedTokens) {
    }

    private record SnapshotPayload(
            List<StoryContextItem> items,
            List<StoryContextSelectionDecision> decisions) {
    }
}
