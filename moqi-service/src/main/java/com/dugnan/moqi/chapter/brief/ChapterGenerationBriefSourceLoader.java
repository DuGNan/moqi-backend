package com.dugnan.moqi.chapter.brief;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief.EntityExplanation;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief.SourceRef;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBriefSource.ConsensusSource;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusCodec;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusDocument;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContentCodec;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.entity.ChapterKeyEventEntity;
import com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity;
import com.dugnan.moqi.knowledge.entity.ForeshadowingItemEntity;
import com.dugnan.moqi.chapter.entitycard.GenerationEntityCard;
import com.dugnan.moqi.chapter.entitycard.GenerationEntityCardRenderer;
import com.dugnan.moqi.chapter.entitycard.GenerationEntityCardSelector;
import com.dugnan.moqi.chapter.entitycard.GenerationEntityCardSelector.Selection;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PlanningModels.ForeshadowingAction;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 从同一作品的当前已确认来源加载章节正文生成说明输入。
 */
@Component
public class ChapterGenerationBriefSourceLoader {

    private static final String PLAN_STATUS_PUBLISHED = "published";
    private static final String PLAN_VALIDITY_CURRENT = "current";

    private static final int PREVIOUS_ENDING_LIMIT = 1200;
    private static final String STATUS_CONFIRMED = "confirmed";

    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterBriefMapper briefMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final ForeshadowingItemMapper foreshadowingMapper;
    private final ChapterSummaryMapper summaryMapper;
    private final ChapterKeyEventMapper eventMapper;
    private final ChapterConsensusCodec consensusCodec;
    private final OutlineCandidateContentCodec outlineCodec;
    private final GenerationEntityCardSelector entityCardSelector;
    private final GenerationEntityCardRenderer entityCardRenderer;

    public ChapterGenerationBriefSourceLoader(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterBriefMapper briefMapper,
            ChapterOutlineQueryMapper outlineMapper,
            ForeshadowingItemMapper foreshadowingMapper,
            ChapterSummaryMapper summaryMapper,
            ChapterKeyEventMapper eventMapper,
            ChapterConsensusCodec consensusCodec,
            OutlineCandidateContentCodec outlineCodec,
            GenerationEntityCardSelector entityCardSelector,
            GenerationEntityCardRenderer entityCardRenderer) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.briefMapper = briefMapper;
        this.outlineMapper = outlineMapper;
        this.foreshadowingMapper = foreshadowingMapper;
        this.summaryMapper = summaryMapper;
        this.eventMapper = eventMapper;
        this.consensusCodec = consensusCodec;
        this.outlineCodec = outlineCodec;
        this.entityCardSelector = entityCardSelector;
        this.entityCardRenderer = entityCardRenderer;
    }

    public ChapterGenerationBriefSource load(Long chapterId, ChapterPlanView plan) {
        ChapterEntity chapter = requireChapter(chapterId);
        WorkEntity work = requireWork(chapter.getWorkId());
        validatePlanScope(chapter, plan);
        ChapterOutlineEntity outline = requireOutline(chapter, plan);
        ChapterBriefEntity brief = requireConfirmedBrief(chapter, outline);
        ChapterEntity previous = previousChapter(chapter);
        PreviousKnowledge previousKnowledge = previousKnowledge(chapter.getWorkId(), previous);
        ConsensusSource consensus = consensus(brief);
        OutlineCandidateContent outlineContent = outlineCodec.read(outline.getOutlineContent());
        EntitySelection selection = entities(
                chapter, consensus, outlineContent, plan.scenes(), previousKnowledge);
        List<SourceRef> sourceRefs = sourceRefs(
                work, chapter, brief, outline, plan, previous, previousKnowledge, selection);
        return new ChapterGenerationBriefSource(
                work.getId(), work.getTitle(), chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                consensus, outlineContent, orderedScenes(plan.scenes()),
                previousEnding(previous), previousKnowledge.summary(), previousKnowledge.events(),
                selection.cards(), selection.explanations(), sourceRefs);
    }

    private WorkEntity requireWork(Long workId) {
        WorkEntity work = workId == null ? null : workMapper.selectById(workId);
        if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return work;
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private void validatePlanScope(ChapterEntity chapter, ChapterPlanView plan) {
        if (plan == null || !chapter.getId().equals(plan.chapterId())
                || !PLAN_STATUS_PUBLISHED.equals(plan.status())
                || !PLAN_VALIDITY_CURRENT.equals(plan.validityStatus())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_SOURCE_STALE, "场景规划不是当前章节的有效已发布版本");
        }
    }

    private ChapterOutlineEntity requireOutline(ChapterEntity chapter, ChapterPlanView plan) {
        ChapterOutlineEntity outline = plan.outlineId() == null ? null : outlineMapper.selectById(plan.outlineId());
        ChapterOutlineEntity latest = outlineMapper.findLatest(chapter.getId());
        if (outline == null || latest == null || Integer.valueOf(1).equals(outline.getDeleted())
                || !outline.getId().equals(latest.getId()) || !chapter.getWorkId().equals(outline.getWorkId())
                || !chapter.getId().equals(outline.getChapterId())
                || !STATUS_CONFIRMED.equals(outline.getOutlineStatus())
                || !"current".equals(outline.getValidityStatus())
                || !plan.outlineRevision().equals(outline.getRevision())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_OUTLINE_STALE, "场景规划绑定的正式章纲已失效");
        }
        return outline;
    }

    private ChapterBriefEntity requireConfirmedBrief(ChapterEntity chapter, ChapterOutlineEntity outline) {
        ChapterBriefEntity brief = outline.getConfirmedBriefId() == null ? null
                : briefMapper.findByIdAndChapterId(outline.getConfirmedBriefId(), chapter.getId());
        if (brief == null || !chapter.getWorkId().equals(brief.getWorkId())
                || !STATUS_CONFIRMED.equals(brief.getBriefStatus())) {
            throw new BusinessException(ErrorCode.CHAPTER_CONFIRMED_BRIEF_REQUIRED, "正式章纲缺少同作品的已确认章节共识");
        }
        return brief;
    }

    private ConsensusSource consensus(ChapterBriefEntity brief) {
        ChapterConsensusDocument document = consensusCodec.read(brief.getBriefContent());
        if (document.consensus() == null) {
            return new ConsensusSource(document.legacyText(), null, null, null, null, null, List.of(), List.of());
        }
        ChapterConsensusContentV1 value = document.consensus();
        ChapterConsensusContentV1.StateChange state = value.stateChange();
        ChapterConsensusContentV1.ReaderProgress reader = value.readerProgress();
        List<String> decisions = value.decisions() == null ? List.of() : value.decisions().stream()
                .filter(item -> STATUS_CONFIRMED.equals(item.status()))
                .sorted(Comparator.comparing(ChapterConsensusContentV1.Decision::key))
                .map(item -> item.title() + "：" + item.candidateSummary())
                .toList();
        return new ConsensusSource(value.chapterTask(), state == null ? null : state.from(),
                state == null ? null : state.to(), value.keyPush(), reader == null ? null : reader.payoff(),
                reader == null ? null : reader.openQuestion(), value.writingBoundaries(), decisions);
    }

    private ChapterEntity previousChapter(ChapterEntity chapter) {
        if (chapter.getChapterNo() == null || chapter.getChapterNo() <= 1) {
            return null;
        }
        return chapterMapper.selectOne(new LambdaQueryWrapper<ChapterEntity>()
                .eq(ChapterEntity::getWorkId, chapter.getWorkId())
                .lt(ChapterEntity::getChapterNo, chapter.getChapterNo())
                .eq(ChapterEntity::getDeleted, 0)
                .orderByDesc(ChapterEntity::getChapterNo)
                .orderByDesc(ChapterEntity::getId)
                .last("LIMIT 1"));
    }

    private PreviousKnowledge previousKnowledge(Long workId, ChapterEntity previous) {
        if (previous == null) {
            return new PreviousKnowledge(null, null, List.of(), List.of());
        }
        ChapterSummaryEntity summary = summaryMapper.selectOne(new LambdaQueryWrapper<ChapterSummaryEntity>()
                .eq(ChapterSummaryEntity::getWorkId, workId)
                .eq(ChapterSummaryEntity::getChapterId, previous.getId())
                .eq(ChapterSummaryEntity::getSummaryStatus, STATUS_CONFIRMED)
                .eq(ChapterSummaryEntity::getDeleted, 0)
                .orderByDesc(ChapterSummaryEntity::getContentRevision)
                .orderByDesc(ChapterSummaryEntity::getId)
                .last("LIMIT 1"));
        List<ChapterKeyEventEntity> events = eventMapper.selectList(
                new LambdaQueryWrapper<ChapterKeyEventEntity>()
                        .eq(ChapterKeyEventEntity::getWorkId, workId)
                        .eq(ChapterKeyEventEntity::getChapterId, previous.getId())
                        .eq(ChapterKeyEventEntity::getDeleted, 0)
                        .orderByAsc(ChapterKeyEventEntity::getOccurredOrder)
                        .orderByAsc(ChapterKeyEventEntity::getId));
        List<String> contents = events.stream()
                .map(item -> item.getEventTitle() + "：" + item.getEventContent()).toList();
        return new PreviousKnowledge(summary == null ? null : summary.getSummary(), summary, contents, events);
    }

    private EntitySelection entities(
            ChapterEntity chapter,
            ConsensusSource consensus,
            OutlineCandidateContent outline,
            List<ScenePlanView> scenes,
            PreviousKnowledge previousKnowledge) {
        Selection cardSelection = entityCardSelector.select(
                chapter.getWorkId(), chapter.getId(), chapter.getChapterNo(), scenes,
                entityChapterTexts(consensus, outline, scenes),
                previousStateTexts(previousKnowledge));
        Set<Long> foreshadowingIds = new LinkedHashSet<>();
        for (ScenePlanView scene : orderedScenes(scenes)) {
            ScenePlanContent content = scene.content();
            content.foreshadowingActions().stream().map(ForeshadowingAction::foreshadowingItemId)
                    .filter(java.util.Objects::nonNull).forEach(foreshadowingIds::add);
        }
        List<ForeshadowingItemEntity> foreshadowings = foreshadowingIds.isEmpty()
                ? List.of() : foreshadowingMapper.selectBatchIds(foreshadowingIds).stream()
                        .sorted(Comparator.comparing(ForeshadowingItemEntity::getId)).toList();
        validateForeshadowings(chapter.getWorkId(), foreshadowingIds, foreshadowings);
        List<EntityExplanation> explanations = new ArrayList<>();
        cardSelection.cards().forEach(card -> explanations.add(new EntityExplanation(
                card.entityId(), card.type(), card.name(), entityCardRenderer.render(card))));
        foreshadowings.forEach(item -> explanations.add(new EntityExplanation(
                item.getId(), "伏笔", item.getTitle(), item.getDescription())));
        explanations.sort(Comparator.comparing(EntityExplanation::type)
                .thenComparing(item -> item.sourceId() == null ? Long.MAX_VALUE : item.sourceId())
                .thenComparing(EntityExplanation::name));
        return new EntitySelection(cardSelection.cards(), explanations, cardSelection.sourceRefs(), foreshadowings);
    }

    private List<String> entityChapterTexts(
            ConsensusSource consensus,
            OutlineCandidateContent outline,
            List<ScenePlanView> scenes) {
        List<String> values = new ArrayList<>();
        add(values, consensus.chapterTask());
        add(values, consensus.openingState());
        add(values, consensus.endingState());
        add(values, consensus.keyPush());
        add(values, consensus.readerPayoff());
        add(values, consensus.openQuestion());
        values.addAll(consensus.writingBoundaries());
        values.addAll(consensus.confirmedDecisions());
        add(values, outline.chapterPurpose());
        add(values, outline.chapterGoal());
        add(values, outline.coreConflict());
        add(values, outline.openingState());
        add(values, outline.endingState());
        add(values, outline.endingHook());
        outline.beats().forEach(beat -> add(values, beat.summary()));
        for (ScenePlanView scene : orderedScenes(scenes)) {
            ScenePlanContent content = scene.content();
            add(values, content.title());
            add(values, content.goal());
            add(values, content.conflict());
            add(values, content.expectedOutcome());
            values.addAll(content.readerMustKnow());
            values.addAll(content.causalPreconditions());
            values.addAll(content.stateChanges());
            values.addAll(content.continuityConstraints());
            values.addAll(content.doNotInvent());
        }
        return values;
    }

    private List<String> previousStateTexts(PreviousKnowledge previousKnowledge) {
        List<String> values = new ArrayList<>();
        add(values, previousKnowledge.summary());
        values.addAll(previousKnowledge.events());
        return values;
    }

    private void add(List<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value.trim());
        }
    }

    private void validateForeshadowings(
            Long workId,
            Set<Long> expectedIds,
            List<ForeshadowingItemEntity> foreshadowings) {
        Map<Long, ForeshadowingItemEntity> actual = foreshadowings.stream()
                .collect(java.util.stream.Collectors.toMap(ForeshadowingItemEntity::getId, item -> item));
        for (Long id : expectedIds) {
            ForeshadowingItemEntity item = actual.get(id);
            if (item == null || Integer.valueOf(1).equals(item.getDeleted())
                    || !workId.equals(item.getWorkId()) || "abandoned".equals(item.getStatus())) {
                throw new BusinessException(ErrorCode.SCENE_PLAN_SOURCE_STALE, "场景规划引用了无效或跨作品伏笔：" + id);
            }
        }
    }

    private List<SourceRef> sourceRefs(
            WorkEntity work,
            ChapterEntity chapter,
            ChapterBriefEntity brief,
            ChapterOutlineEntity outline,
            ChapterPlanView plan,
            ChapterEntity previous,
            PreviousKnowledge previousKnowledge,
            EntitySelection selection) {
        List<SourceRef> refs = new ArrayList<>();
        refs.add(ref("WORK", work.getId(), work.getVersion()));
        refs.add(ref("CHAPTER", chapter.getId(), chapter.getVersion()));
        refs.add(ref("CHAPTER_CONSENSUS", brief.getId(), brief.getVersion()));
        refs.add(ref("CHAPTER_OUTLINE", outline.getId(), outline.getRevision() + ":" + outline.getVersion()));
        refs.add(ref("CHAPTER_PLAN", plan.id(), plan.planNo() + ":" + plan.version()));
        orderedScenes(plan.scenes()).forEach(scene -> refs.add(ref("SCENE_PLAN", scene.scenePlanId(),
                plan.version() + ":" + scene.contentSchemaVersion())));
        if (previous != null && StringUtils.hasText(previous.getContent())) {
            refs.add(ref("PREVIOUS_CHAPTER_CONTENT", previous.getId(), previous.getVersion()));
        }
        if (previousKnowledge.summaryEntity() != null) {
            ChapterSummaryEntity summary = previousKnowledge.summaryEntity();
            refs.add(ref("CHAPTER_SUMMARY", summary.getId(),
                    summary.getContentRevision() + ":" + summary.getVersion()));
        }
        previousKnowledge.eventEntities().forEach(item -> refs.add(
                ref("CHAPTER_KEY_EVENT", item.getId(), item.getVersion())));
        refs.addAll(selection.sourceRefs());
        selection.foreshadowings().forEach(item -> refs.add(ref("FORESHADOWING", item.getId(), item.getVersion())));
        refs.sort(Comparator.comparing(SourceRef::sourceType).thenComparing(SourceRef::sourceId));
        return List.copyOf(refs);
    }

    private SourceRef ref(String type, Long id, Object version) {
        return new SourceRef(type, String.valueOf(id), String.valueOf(version));
    }

    private List<ScenePlanView> orderedScenes(List<ScenePlanView> scenes) {
        return scenes == null ? List.of() : scenes.stream()
                .filter(scene -> "planned".equals(scene.content().status()))
                .sorted(Comparator.comparing(ScenePlanView::sequence).thenComparing(ScenePlanView::scenePlanId))
                .toList();
    }

    private String previousEnding(ChapterEntity previous) {
        if (previous == null || !StringUtils.hasText(previous.getContent())) {
            return null;
        }
        String content = previous.getContent().trim();
        return content.length() <= PREVIOUS_ENDING_LIMIT
                ? content : content.substring(content.length() - PREVIOUS_ENDING_LIMIT);
    }

    private record PreviousKnowledge(
            String summary,
            ChapterSummaryEntity summaryEntity,
            List<String> events,
            List<ChapterKeyEventEntity> eventEntities) {
    }

    private record EntitySelection(
            List<GenerationEntityCard> cards,
            List<EntityExplanation> explanations,
            List<SourceRef> sourceRefs,
            List<ForeshadowingItemEntity> foreshadowings) {
    }
}
