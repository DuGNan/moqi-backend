package com.dugnan.moqi.chapter.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusCodec;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusDocument;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entitycard.GenerationEntityCardRenderer;
import com.dugnan.moqi.chapter.entitycard.GenerationEntityCardSelector;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Beat;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContentCodec;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.entity.SettingEntryEntity;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PlanningModels.PlanReference;
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
 * @description 验证章节正文生成说明只读取当前、已确认且属于同一作品的来源。
 */
class ChapterGenerationBriefSourceLoaderTest {

    private final WorkMapper workMapper = mock(WorkMapper.class);
    private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
    private final ChapterBriefMapper briefMapper = mock(ChapterBriefMapper.class);
    private final ChapterOutlineQueryMapper outlineMapper = mock(ChapterOutlineQueryMapper.class);
    private final SettingEntryMapper settingMapper = mock(SettingEntryMapper.class);
    private final ForeshadowingItemMapper foreshadowingMapper = mock(ForeshadowingItemMapper.class);
    private final ChapterSummaryMapper summaryMapper = mock(ChapterSummaryMapper.class);
    private final ChapterKeyEventMapper eventMapper = mock(ChapterKeyEventMapper.class);
    private final ChapterConsensusCodec consensusCodec = mock(ChapterConsensusCodec.class);
    private final OutlineCandidateContentCodec outlineCodec = mock(OutlineCandidateContentCodec.class);
    private ChapterGenerationBriefSourceLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ChapterGenerationBriefSourceLoader(
                workMapper, chapterMapper, briefMapper, outlineMapper, foreshadowingMapper,
                summaryMapper, eventMapper, consensusCodec, outlineCodec,
                new GenerationEntityCardSelector(settingMapper, new ObjectMapper()),
                new GenerationEntityCardRenderer());
        when(settingMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void loadsFirstChapterWithoutInventingPreviousChapterSources() {
        ChapterPlanView plan = plan(new PlanReference(null, "林风"));
        stubCore(plan, 1);

        ChapterGenerationBriefSource source = loader.load(12L, plan);

        assertThat(source.previousChapterEnding()).isNull();
        assertThat(source.previousChapterSummary()).isNull();
        assertThat(source.previousKeyEvents()).isEmpty();
        assertThat(source.entityExplanations()).singleElement().satisfies(entity -> {
            assertThat(entity.name()).isEqualTo("林风");
            assertThat(entity.explanation()).contains("不得补造");
        });
        assertThat(source.entityCards()).singleElement().satisfies(card -> {
            assertThat(card.entityId()).isNull();
            assertThat(card.firstEstablishedInChapter()).isFalse();
        });
        verify(summaryMapper, never()).selectOne(any());
        verify(eventMapper, never()).selectList(any());
    }

    @Test
    void rejectsAReferencedSettingFromAnotherWork() {
        ChapterPlanView plan = plan(new PlanReference(501L, "林风"));
        stubCore(plan, 2);
        when(chapterMapper.selectOne(any())).thenReturn(null);
        SettingEntryEntity setting = new SettingEntryEntity();
        setting.setId(501L);
        setting.setWorkId(99L);
        setting.setName("林风");
        setting.setSettingType("character");
        setting.setContent("跨作品人物");
        setting.setEntryStatus("active");
        setting.setDeleted(0);
        when(settingMapper.selectBatchIds(any())).thenReturn(List.of(setting));

        assertThatThrownBy(() -> loader.load(12L, plan))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SCENE_PLAN_SOURCE_STALE))
                .hasMessageContaining("跨作品设定");
    }

    @Test
    void rejectsAnUnconfirmedConsensusBoundToTheOutline() {
        ChapterPlanView plan = plan(new PlanReference(null, "林风"));
        stubCore(plan, 1);
        ChapterBriefEntity draft = new ChapterBriefEntity();
        draft.setId(31L);
        draft.setWorkId(2L);
        draft.setChapterId(12L);
        draft.setBriefStatus("draft");
        draft.setDeleted(0);
        when(briefMapper.findByIdAndChapterId(31L, 12L)).thenReturn(draft);

        assertThatThrownBy(() -> loader.load(12L, plan))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.CHAPTER_CONFIRMED_BRIEF_REQUIRED));
    }

    private void stubCore(ChapterPlanView plan, int chapterNo) {
        WorkEntity work = new WorkEntity();
        work.setId(2L);
        work.setTitle("长夜号");
        work.setVersion(1);
        work.setDeleted(0);
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(12L);
        chapter.setWorkId(2L);
        chapter.setChapterNo(chapterNo);
        chapter.setTitle("警报");
        chapter.setVersion(3);
        chapter.setDeleted(0);
        ChapterOutlineEntity outline = new ChapterOutlineEntity();
        outline.setId(21L);
        outline.setWorkId(2L);
        outline.setChapterId(12L);
        outline.setConfirmedBriefId(31L);
        outline.setOutlineStatus("confirmed");
        outline.setOutlineContent("outline-json");
        outline.setRevision(4);
        outline.setValidityStatus("current");
        outline.setVersion(2);
        outline.setDeleted(0);
        ChapterBriefEntity brief = new ChapterBriefEntity();
        brief.setId(31L);
        brief.setWorkId(2L);
        brief.setChapterId(12L);
        brief.setBriefStatus("confirmed");
        brief.setBriefContent("confirmed-consensus");
        brief.setVersion(5);
        brief.setDeleted(0);
        when(chapterMapper.selectById(12L)).thenReturn(chapter);
        when(workMapper.selectById(2L)).thenReturn(work);
        when(outlineMapper.selectById(21L)).thenReturn(outline);
        when(outlineMapper.findLatest(12L)).thenReturn(outline);
        when(briefMapper.findByIdAndChapterId(31L, 12L)).thenReturn(brief);
        when(consensusCodec.read("confirmed-consensus")).thenReturn(ChapterConsensusDocument.legacy("章节任务"));
        when(outlineCodec.read("outline-json")).thenReturn(new OutlineCandidateContent(
                2, "章节作用", "开场", "目标", "冲突", List.of(new Beat("beat-1", "推进")),
                "转折", "结尾", "钩子", List.of()));
    }

    private ChapterPlanView plan(PlanReference viewpoint) {
        ScenePlanContent scene = new ScenePlanContent(
                "scene-1", 1, "警报", viewpoint, "深夜", null, "目标", "冲突", "紧张", "快速",
                List.of(), List.of(), List.of(), "结果", "planned", List.of("beat-1"), List.of(), List.of(), "",
                List.of(), List.of(), "core", List.of(), List.of());
        return new ChapterPlanView(
                41L, 12L, 6, "published", 51L, 2, 21L, 4, null, null, null,
                List.of(new ScenePlanView(61L, "scene-1", 1, 2, scene)), 2, "not_required",
                null, null, List.of(), "current", List.of(), null, null, 7,
                LocalDateTime.of(2026, 8, 14, 9, 0), LocalDateTime.of(2026, 8, 14, 9, 0));
    }
}
