package com.dugnan.moqi.chapter.service.impl;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBriefCompiler;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBriefSourceLoader;
import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefPreview;
import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefSourceRef;
import com.dugnan.moqi.chapter.dto.ChapterGenerationEntityCardModels.EntityCardPreview;
import com.dugnan.moqi.chapter.dto.ChapterGenerationEntityCardModels.EntityCardView;
import com.dugnan.moqi.chapter.entitycard.GenerationEntityCardFingerprint;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PublishedScenePlanQueryPort;
import com.dugnan.moqi.planning.ScenePlanConsistencyService;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 编译当前有效来源并提供不调用模型、不写权威资产的章节正文生成说明预览。
 */
@Service
public class ChapterGenerationBriefServiceImpl implements ChapterGenerationBriefService {

    public static final String ENTITY_CARD_TEMPLATE_VERSION = "chapter-generation-entity-cards-v1";

    private final PublishedScenePlanQueryPort scenePlanQueryPort;
    private final ScenePlanConsistencyService consistencyService;
    private final ChapterGenerationBriefSourceLoader sourceLoader;
    private final ChapterGenerationBriefCompiler compiler;
    private final GenerationEntityCardFingerprint entityCardFingerprint;

    public ChapterGenerationBriefServiceImpl(
            PublishedScenePlanQueryPort scenePlanQueryPort,
            ScenePlanConsistencyService consistencyService,
            ChapterGenerationBriefSourceLoader sourceLoader,
            ChapterGenerationBriefCompiler compiler,
            GenerationEntityCardFingerprint entityCardFingerprint) {
        this.scenePlanQueryPort = scenePlanQueryPort;
        this.consistencyService = consistencyService;
        this.sourceLoader = sourceLoader;
        this.compiler = compiler;
        this.entityCardFingerprint = entityCardFingerprint;
    }

    @Override
    public ChapterGenerationBrief compile(Long chapterId, ChapterPlanView plan) {
        consistencyService.requireGenerationAllowed(chapterId, plan.id());
        return compiler.compile(sourceLoader.load(chapterId, plan));
    }

    @Override
    public GenerationBriefPreview preview(Long chapterId, Integer scenePlanNo) {
        ChapterPlanView plan = scenePlanNo == null
                ? scenePlanQueryPort.loadCurrent(chapterId)
                : scenePlanQueryPort.loadPublished(chapterId, scenePlanNo);
        ChapterGenerationBrief brief = compile(chapterId, plan);
        return new GenerationBriefPreview(
                brief.workId(), brief.chapterId(), plan.id(), plan.planNo(), brief.templateVersion(),
                plan.validityStatus(), brief.sourceRefs().stream()
                        .map(ref -> new GenerationBriefSourceRef(
                                ref.sourceType(), ref.sourceId(), ref.contentVersion()))
                        .toList(),
                brief.fingerprint(), brief.compiledAt(), brief.content());
    }

    @Override
    public EntityCardPreview previewEntityCards(Long chapterId, Integer scenePlanNo) {
        ChapterPlanView plan = scenePlanNo == null
                ? scenePlanQueryPort.loadCurrent(chapterId)
                : scenePlanQueryPort.loadPublished(chapterId, scenePlanNo);
        consistencyService.requireGenerationAllowed(chapterId, plan.id());
        var source = sourceLoader.load(chapterId, plan);
        String fingerprint = entityCardFingerprint.calculate(
                ENTITY_CARD_TEMPLATE_VERSION, source.entityCards(), source.sourceRefs());
        return new EntityCardPreview(
                source.workId(), source.chapterId(), plan.id(), plan.planNo(), ENTITY_CARD_TEMPLATE_VERSION,
                plan.validityStatus(), source.sourceRefs().stream()
                        .map(ref -> new GenerationBriefSourceRef(
                                ref.sourceType(), ref.sourceId(), ref.contentVersion()))
                        .toList(),
                fingerprint, LocalDateTime.now(), source.entityCards().stream()
                        .map(card -> new EntityCardView(
                                card.entityId(), card.type(), card.name(), card.aliases(), card.affiliation(),
                                card.storyRole(), card.currentState(), card.characterKnowledge(),
                                card.firstAppearanceExplanation(), card.prohibitedInference(),
                                card.firstEstablishedInChapter(), card.confirmedDescription(), card.sourceVersion()))
                        .toList());
    }
}
