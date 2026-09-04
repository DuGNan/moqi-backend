package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.service.ProseObjectPromptContextService.ProseObjectDraft;
import com.dugnan.moqi.chapter.service.ProseObjectTargetService;
import com.dugnan.moqi.chapter.service.ProseObjectTargetService.ProseObjectTarget;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-09-04
 * @description 验证正文对象、冻结依据和未保存草稿的模型可见边界。
 */
class ProseObjectPromptContextServiceImplTest {

    @Test
    void rendersFrozenBasisWithoutInternalIdentifiersOrFieldNames() {
        ProseObjectTargetService targetService = mock(ProseObjectTargetService.class);
        ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        ProseObjectTarget target = new ProseObjectTarget(
                "candidate:8", "candidate", 3, "saved-hash", "作者编辑后的候选正文", "基于规划生成", 17L);
        when(targetService.resolve(2L, "candidate:8")).thenReturn(target);
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(17L);
        generation.setDeleted(0);
        generation.setGeneratedContent("候选创建时正文");
        generation.setBasisSnapshotJson("""
                {"chapterGenerationBrief":{
                  "chapterPurpose":"推进调查","chapterGoal":"拿到证据","coreConflict":"是否相信证人",
                  "openingConditions":["雨夜抵达仓库"],"requiredEndingState":["主角取得钥匙"],
                  "eventCausality":["跟踪导致身份暴露"],"stateChanges":["主角开始怀疑同伴"],
                  "characterConstraints":["林风保持克制"],
                  "entityExplanations":[{"type":"地点","name":"旧仓库","explanation":"位于港区"}],
                  "creativeFreedom":["允许调整节奏"],"prohibitedInventions":["不得新增超能力"]},
                 "currentProseBasis":{"content":"上一章以警报响起结束","contentHash":"hidden-hash"}}
                """);
        when(generationMapper.selectById(17L)).thenReturn(generation);
        var service = new ProseObjectPromptContextServiceImpl(
                targetService, generationMapper, new ObjectMapper());

        String prompt = service.freeze(2L, "candidate:8", null).modelText();

        assertThat(prompt)
                .contains("作者当前保存的正文", "推进调查", "拿到证据", "林风保持克制")
                .contains("上一章以警报响起结束", "当前保存内容在创建后经过作者编辑")
                .contains("两者不一致时，必须指出差异并以作者当前确认内容为准")
                .doesNotContain("candidate:8", "saved-hash", "hidden-hash", "chapterPurpose",
                        "sourceGenerationId", "contentHash");
    }

    @Test
    void labelsDraftAsUnsavedAndRejectsStaleBaseline() {
        ProseObjectTargetService targetService = mock(ProseObjectTargetService.class);
        ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        ProseObjectTarget target = new ProseObjectTarget(
                "formal:2", "formal", 4, "saved-hash", "已保存正文", "正式正文", null);
        when(targetService.resolve(2L, "formal:2")).thenReturn(target);
        var service = new ProseObjectPromptContextServiceImpl(
                targetService, generationMapper, new ObjectMapper());

        String prompt = service.freeze(2L, "formal:2",
                new ProseObjectDraft(4, "saved-hash", "尚未保存的编辑器草稿")).modelText();

        assertThat(prompt)
                .contains("已保存正文", "未保存编辑器草稿", "只用于本轮讨论或生成修改提案")
                .contains("不代表已经保存、采纳或发布");
        assertThatThrownBy(() -> service.freeze(2L, "formal:2",
                new ProseObjectDraft(3, "old-hash", "过期草稿")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("基线已经变化");
    }

    @Test
    void freezesEachCandidateWithItsOwnSavedTextAndCreationBasis() {
        ProseObjectTargetService targetService = mock(ProseObjectTargetService.class);
        ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        when(targetService.resolve(2L, "candidate:8")).thenReturn(new ProseObjectTarget(
                "candidate:8", "candidate", 0, "hash-eight", "候选八保存正文", "正文候选", 17L));
        when(targetService.resolve(2L, "candidate:9")).thenReturn(new ProseObjectTarget(
                "candidate:9", "candidate", 0, "hash-nine", "候选九保存正文", "正文候选", 18L));
        when(generationMapper.selectById(17L)).thenReturn(generation(
                17L, "候选八创建正文", "候选八要求保留雨夜仓库"));
        when(generationMapper.selectById(18L)).thenReturn(generation(
                18L, "候选九创建正文", "候选九要求保留白昼站台"));
        var service = new ProseObjectPromptContextServiceImpl(
                targetService, generationMapper, new ObjectMapper());

        String candidateEight = service.freeze(2L, "candidate:8", null).modelText();
        String candidateNine = service.freeze(2L, "candidate:9", null).modelText();

        assertThat(candidateEight)
                .contains("候选八保存正文", "候选八要求保留雨夜仓库")
                .doesNotContain("候选九保存正文", "候选九要求保留白昼站台");
        assertThat(candidateNine)
                .contains("候选九保存正文", "候选九要求保留白昼站台")
                .doesNotContain("候选八保存正文", "候选八要求保留雨夜仓库");
    }

    private ChapterGenerationEntity generation(Long id, String content, String chapterGoal) {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(id);
        generation.setDeleted(0);
        generation.setGeneratedContent(content);
        generation.setBasisSnapshotJson("{\"chapterGenerationBrief\":{\"chapterGoal\":\""
                + chapterGoal + "\"}}");
        return generation;
    }
}
