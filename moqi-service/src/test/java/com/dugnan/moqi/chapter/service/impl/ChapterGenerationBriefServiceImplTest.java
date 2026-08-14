package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBriefCompiler;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBriefSource;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBriefSourceLoader;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PublishedScenePlanQueryPort;
import com.dugnan.moqi.planning.ScenePlanConsistencyService;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 验证章节正文生成说明预览复用生成门禁和统一编译结果。
 */
class ChapterGenerationBriefServiceImplTest {

    @Test
    void previewsASpecificPublishedPlanThroughTheSameValidatedCompiler() {
        PublishedScenePlanQueryPort queryPort = mock(PublishedScenePlanQueryPort.class);
        ScenePlanConsistencyService consistencyService = mock(ScenePlanConsistencyService.class);
        ChapterGenerationBriefSourceLoader sourceLoader = mock(ChapterGenerationBriefSourceLoader.class);
        ChapterGenerationBriefCompiler compiler = mock(ChapterGenerationBriefCompiler.class);
        ChapterGenerationBriefServiceImpl service = new ChapterGenerationBriefServiceImpl(
                queryPort, consistencyService, sourceLoader, compiler);
        ChapterPlanView plan = mock(ChapterPlanView.class);
        ChapterGenerationBriefSource source = mock(ChapterGenerationBriefSource.class);
        when(plan.id()).thenReturn(41L);
        when(plan.planNo()).thenReturn(6);
        when(plan.validityStatus()).thenReturn("current");
        when(queryPort.loadPublished(12L, 6)).thenReturn(plan);
        when(sourceLoader.load(12L, plan)).thenReturn(source);
        when(compiler.compile(source)).thenReturn(brief());

        var result = service.preview(12L, 6);

        assertThat(result.fingerprint()).isEqualTo("brief-hash");
        assertThat(result.content()).contains("Chapter Generation Brief");
        assertThat(result.sourceRefs()).singleElement()
                .extracting("sourceType", "sourceId", "contentVersion")
                .containsExactly("CHAPTER_OUTLINE", "21", "4:2");
        verify(consistencyService).requireGenerationAllowed(12L, 41L);
        verify(queryPort).loadPublished(12L, 6);
    }

    @Test
    void repeatedCurrentPreviewReturnsTheSameFingerprintWithoutPersistingState() {
        PublishedScenePlanQueryPort queryPort = mock(PublishedScenePlanQueryPort.class);
        ScenePlanConsistencyService consistencyService = mock(ScenePlanConsistencyService.class);
        ChapterGenerationBriefSourceLoader sourceLoader = mock(ChapterGenerationBriefSourceLoader.class);
        ChapterGenerationBriefCompiler compiler = mock(ChapterGenerationBriefCompiler.class);
        ChapterGenerationBriefServiceImpl service = new ChapterGenerationBriefServiceImpl(
                queryPort, consistencyService, sourceLoader, compiler);
        ChapterPlanView plan = mock(ChapterPlanView.class);
        ChapterGenerationBriefSource source = mock(ChapterGenerationBriefSource.class);
        when(plan.id()).thenReturn(41L);
        when(plan.planNo()).thenReturn(6);
        when(plan.validityStatus()).thenReturn("current");
        when(queryPort.loadCurrent(12L)).thenReturn(plan);
        when(sourceLoader.load(12L, plan)).thenReturn(source);
        when(compiler.compile(source)).thenReturn(brief());

        var first = service.preview(12L, null);
        var second = service.preview(12L, null);

        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.content()).isEqualTo(second.content());
        verify(queryPort, times(2)).loadCurrent(12L);
        verify(consistencyService, times(2)).requireGenerationAllowed(12L, 41L);
    }

    private ChapterGenerationBrief brief() {
        return new ChapterGenerationBrief(
                1, "chapter-generation-brief-v1", 2L, "作品", 12L, 1, "章节", "作用", "目标", "冲突",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ChapterGenerationBrief.SourceRef("CHAPTER_OUTLINE", "21", "4:2")),
                "brief-hash", LocalDateTime.of(2026, 8, 14, 10, 0), "# Chapter Generation Brief");
    }
}
