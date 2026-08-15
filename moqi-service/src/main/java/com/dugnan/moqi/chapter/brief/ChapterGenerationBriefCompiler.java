package com.dugnan.moqi.chapter.brief;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.planning.PlanningModels.PlanReference;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 将固定且已校验的作品来源编译为确定性的章节正文生成说明。
 */
@Component
public class ChapterGenerationBriefCompiler {

    public static final int SCHEMA_VERSION = 1;
    public static final String TEMPLATE_VERSION = "chapter-generation-brief-v2-entity-cards";

    private final ChapterGenerationBriefFingerprint fingerprint;
    private final ChapterGenerationBriefRenderer renderer;

    public ChapterGenerationBriefCompiler(
            ChapterGenerationBriefFingerprint fingerprint,
            ChapterGenerationBriefRenderer renderer) {
        this.fingerprint = fingerprint;
        this.renderer = renderer;
    }

    public ChapterGenerationBrief compile(ChapterGenerationBriefSource source) {
        return compile(source, LocalDateTime.now());
    }

    ChapterGenerationBrief compile(ChapterGenerationBriefSource source, LocalDateTime compiledAt) {
        List<String> openingConditions = openingConditions(source);
        List<String> readerKnowledge = collect(source.scenes(), ScenePlanContent::readerMustKnow);
        List<String> eventCausality = eventCausality(source);
        List<String> stateChanges = collect(source.scenes(), ScenePlanContent::stateChanges);
        List<String> characterConstraints = characterConstraints(source.scenes());
        List<String> requiredEndingState = requiredEndingState(source);
        List<String> creativeFreedom = collect(source.scenes(), ScenePlanContent::optionalExpression);
        List<String> prohibitedInventions = prohibitedInventions(source);
        String inputFingerprint = fingerprint.calculate(TEMPLATE_VERSION, source);
        ChapterGenerationBrief structured = new ChapterGenerationBrief(
                SCHEMA_VERSION, TEMPLATE_VERSION, source.workId(), source.workTitle(), source.chapterId(),
                source.chapterNo(), source.chapterTitle(), chapterPurpose(source),
                source.outline().chapterGoal(), source.outline().coreConflict(), openingConditions,
                readerKnowledge, eventCausality, stateChanges, characterConstraints,
                source.entityExplanations(), requiredEndingState, creativeFreedom, prohibitedInventions,
                source.sourceRefs(), inputFingerprint, compiledAt, null);
        return new ChapterGenerationBrief(
                structured.schemaVersion(), structured.templateVersion(), structured.workId(), structured.workTitle(),
                structured.chapterId(), structured.chapterNo(), structured.chapterTitle(), structured.chapterPurpose(),
                structured.chapterGoal(), structured.coreConflict(), structured.openingConditions(),
                structured.readerKnowledge(), structured.eventCausality(), structured.stateChanges(),
                structured.characterConstraints(), structured.entityExplanations(), structured.requiredEndingState(),
                structured.creativeFreedom(), structured.prohibitedInventions(), structured.sourceRefs(),
                structured.fingerprint(), structured.compiledAt(), renderer.render(structured));
    }

    private List<String> openingConditions(ChapterGenerationBriefSource source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        add(result, source.consensus().openingState());
        add(result, source.outline().openingState());
        add(result, source.previousChapterSummary() == null ? null : "上章已确认摘要：" + source.previousChapterSummary());
        add(result, source.previousChapterEnding() == null ? null : "上章结尾原文：" + source.previousChapterEnding());
        if (!source.scenes().isEmpty()) {
            ScenePlanContent first = source.scenes().get(0).content();
            add(result, "首场时间：" + marked(first.timeAnchor()));
            add(result, "首场地点：" + reference(first.location()));
            add(result, "首场视角：" + reference(first.viewpointCharacter()));
            add(result, first.locationTransition());
        }
        return List.copyOf(result);
    }

    private String chapterPurpose(ChapterGenerationBriefSource source) {
        if (!StringUtils.hasText(source.outline().chapterPurpose())) {
            return source.consensus().chapterTask();
        }
        if (!StringUtils.hasText(source.consensus().chapterTask())
                || source.outline().chapterPurpose().trim().equals(source.consensus().chapterTask().trim())) {
            return source.outline().chapterPurpose();
        }
        return source.consensus().chapterTask().trim() + "；章纲作用：" + source.outline().chapterPurpose().trim();
    }

    private List<String> eventCausality(ChapterGenerationBriefSource source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        add(result, source.consensus().keyPush() == null ? null : "已确认关键推进：" + source.consensus().keyPush());
        source.consensus().confirmedDecisions().forEach(value -> add(result, "已确认决策：" + value));
        source.previousKeyEvents().forEach(event -> add(result, "上章关键事件：" + event));
        for (ScenePlanView scene : source.scenes()) {
            ScenePlanContent content = scene.content();
            String prefix = "场景" + scene.sequence() + "「" + content.title() + "」";
            for (String precondition : content.causalPreconditions()) {
                add(result, prefix + "因果前置：" + precondition);
            }
            add(result, prefix + "推进：" + content.goal() + "；冲突：" + content.conflict()
                    + "；结果：" + content.expectedOutcome());
        }
        return List.copyOf(result);
    }

    private List<String> characterConstraints(List<ScenePlanView> scenes) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ScenePlanView scene : scenes) {
            ScenePlanContent content = scene.content();
            String prefix = "场景" + scene.sequence() + "「" + content.title() + "」";
            add(result, prefix + "视角人物：" + reference(content.viewpointCharacter()) + "；目标：" + content.goal());
            if (!content.participants().isEmpty()) {
                add(result, prefix + "在场人物：" + content.participants().stream()
                        .map(this::reference).collect(java.util.stream.Collectors.joining("、")));
            }
            content.continuityConstraints().forEach(value -> add(result, prefix + "认知或连续性边界：" + value));
        }
        return List.copyOf(result);
    }

    private List<String> requiredEndingState(ChapterGenerationBriefSource source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        add(result, source.consensus().endingState());
        add(result, source.outline().endingState());
        add(result, source.consensus().readerPayoff() == null
                ? null : "本章阅读回报：" + source.consensus().readerPayoff());
        add(result, source.consensus().openQuestion() == null
                ? null : "结尾保留问题：" + source.consensus().openQuestion());
        add(result, source.outline().endingHook() == null ? null : "结尾钩子：" + source.outline().endingHook());
        if (!source.scenes().isEmpty()) {
            ScenePlanContent last = source.scenes().get(source.scenes().size() - 1).content();
            add(result, last.expectedOutcome());
            last.stateChanges().forEach(value -> add(result, value));
        }
        return List.copyOf(result);
    }

    private List<String> prohibitedInventions(ChapterGenerationBriefSource source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        source.consensus().writingBoundaries().forEach(value -> add(result, value));
        source.outline().constraints().forEach(value -> add(result, value));
        collect(source.scenes(), ScenePlanContent::doNotInvent).forEach(value -> add(result, value));
        add(result, "不得把候选、否定或未确认资料写成事实；不得擅自确认或修改权威资产。");
        return List.copyOf(result);
    }

    private List<String> collect(
            List<ScenePlanView> scenes,
            java.util.function.Function<ScenePlanContent, List<String>> extractor) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ScenePlanView scene : scenes) {
            extractor.apply(scene.content()).forEach(value -> add(result, value));
        }
        return List.copyOf(result);
    }

    private String reference(PlanReference reference) {
        return reference == null || !StringUtils.hasText(reference.name()) ? "未标注" : reference.name().trim();
    }

    private String marked(String value) {
        return StringUtils.hasText(value) ? value.trim() : "未标注";
    }

    private void add(LinkedHashSet<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value.trim());
        }
    }
}
