package com.dugnan.moqi.chapter.service.impl;

import org.springframework.stereotype.Service;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBriefCompiler;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBriefSourceLoader;
import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefPreview;
import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefSourceRef;
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

    private final PublishedScenePlanQueryPort scenePlanQueryPort;
    private final ScenePlanConsistencyService consistencyService;
    private final ChapterGenerationBriefSourceLoader sourceLoader;
    private final ChapterGenerationBriefCompiler compiler;

    public ChapterGenerationBriefServiceImpl(
            PublishedScenePlanQueryPort scenePlanQueryPort,
            ScenePlanConsistencyService consistencyService,
            ChapterGenerationBriefSourceLoader sourceLoader,
            ChapterGenerationBriefCompiler compiler) {
        this.scenePlanQueryPort = scenePlanQueryPort;
        this.consistencyService = consistencyService;
        this.sourceLoader = sourceLoader;
        this.compiler = compiler;
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
}
