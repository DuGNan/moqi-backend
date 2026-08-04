package com.dugnan.moqi.sourcechain;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.sourcechain.entity.ChapterAssetValidityAuditEntity;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetValidityAuditMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 校验来源传播的重复事件幂等和正文快照回写。
 */
class SourcePropagationServiceImplTest {
    @Test
    void keepsConfirmedOutlineCurrentWhileInvalidatingItsDownstreamAssets() {
        ChapterAssetSourceChainService sourceChain = mock(ChapterAssetSourceChainService.class);
        ChapterOutlineQueryMapper outlineMapper = mock(ChapterOutlineQueryMapper.class);
        com.dugnan.moqi.work.entity.ChapterOutlineEntity outline = new com.dugnan.moqi.work.entity.ChapterOutlineEntity();
        outline.setId(12L); outline.setWorkId(3L); outline.setRevision(2);
        when(outlineMapper.selectById(12L)).thenReturn(outline);
        when(sourceChain.recordSnapshot(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(77L);
        SourcePropagationServiceImpl service = new SourcePropagationServiceImpl(sourceChain, mock(ChapterPlanVersionMapper.class), mock(ChapterGenerationMapper.class), outlineMapper);
        service.outlineConfirmed(8L, 12L);
        verify(sourceChain).markNeedsReview(8L, "outline:12", java.util.List.of("outline_changed"), false, true, true);
    }

    @Test
    void keepsPublishedScenePlanCurrentWhileInvalidatingOnlyGenerations() {
        ChapterAssetSourceChainService sourceChain = mock(ChapterAssetSourceChainService.class);
        SourcePropagationServiceImpl service = new SourcePropagationServiceImpl(sourceChain, mock(ChapterPlanVersionMapper.class), mock(ChapterGenerationMapper.class), mock(ChapterOutlineQueryMapper.class));
        service.scenePlanPublished(8L, 21L);
        verify(sourceChain).markNeedsReview(8L, "scene-plan:21", java.util.List.of("scene_plan_changed"), false, false, true);
    }

    @Test
    void ignoresRepeatedEventWithoutUpdatingAssets() {
        ChapterPlanVersionMapper planMapper = mock(ChapterPlanVersionMapper.class);
        ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        ChapterAssetValidityAuditMapper auditMapper = mock(ChapterAssetValidityAuditMapper.class);
        when(auditMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ChapterAssetValidityAuditEntity>>any()))
                .thenReturn(new ChapterAssetValidityAuditEntity());
        ChapterAssetSourceChainServiceImpl service = new ChapterAssetSourceChainServiceImpl(
                mock(ChapterOutlineQueryMapper.class), planMapper, generationMapper,
                mock(ChapterAssetSourceSnapshotMapper.class), auditMapper, new ObjectMapper());

        service.markNeedsReview(9L, "consensus:18", java.util.List.of("consensus_changed"));

        verify(auditMapper, never()).insert(org.mockito.ArgumentMatchers.<ChapterAssetValidityAuditEntity>any());
        verify(planMapper, never()).update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(generationMapper, never()).update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void writesCreatedGenerationSnapshotBackToGeneration() {
        ChapterAssetSourceChainService sourceChain = mock(ChapterAssetSourceChainService.class);
        when(sourceChain.recordSnapshot(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any())).thenReturn(88L);
        ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(31L);
        generation.setWorkId(4L);
        generation.setVersion(2);
        when(generationMapper.selectById(31L)).thenReturn(generation);
        SourcePropagationServiceImpl service = new SourcePropagationServiceImpl(sourceChain,
                mock(ChapterPlanVersionMapper.class), generationMapper, mock(ChapterOutlineQueryMapper.class));

        service.generationCreated(7L, 31L);

        org.assertj.core.api.Assertions.assertThat(generation.getSourceSnapshotId()).isEqualTo(88L);
        verify(generationMapper).updateById(generation);
    }
}
