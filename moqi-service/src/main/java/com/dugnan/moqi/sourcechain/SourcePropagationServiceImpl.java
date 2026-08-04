package com.dugnan.moqi.sourcechain;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.sourcechain.dto.ChapterAssetSourceChainModels.AssetSourceView;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 以幂等更新方式执行已确认上游资产的下游复核传播。
 */
@Service
public class SourcePropagationServiceImpl implements SourcePropagationService {
    private final ChapterAssetSourceChainService sourceChainService;
    private final ChapterPlanVersionMapper planMapper;
    private final ChapterGenerationMapper generationMapper;
    private final ChapterOutlineQueryMapper outlineMapper;

    public SourcePropagationServiceImpl(ChapterAssetSourceChainService sourceChainService,
            ChapterPlanVersionMapper planMapper, ChapterGenerationMapper generationMapper,
            ChapterOutlineQueryMapper outlineMapper) {
        this.sourceChainService = sourceChainService;
        this.planMapper = planMapper;
        this.generationMapper = generationMapper;
        this.outlineMapper = outlineMapper;
    }

    @Override public void consensusConfirmed(Long chapterId, Long consensusId) {
        sourceChainService.markNeedsReview(chapterId, "consensus:" + consensusId, List.of("consensus_changed"));
    }

    @Override public void outlineConfirmed(Long chapterId, Long outlineId) {
        ChapterOutlineEntity outline = outlineMapper.selectById(outlineId);
        if (outline != null) {
            Long snapshotId = sourceChainService.recordSnapshot(outline.getWorkId(), chapterId, new AssetSourceView("outline",
                    outlineId, outline.getRevision(), null, "current", List.of(), outline.getConfirmedBriefId(), null,
                    outlineId, outline.getRevision(), null, null));
            outline.setSourceSnapshotId(snapshotId);
            outline.setValidityStatus("current");
            outlineMapper.updateById(outline);
        }
        sourceChainService.markNeedsReview(chapterId, "outline:" + outlineId, List.of("outline_changed"), false, true, true);
    }

    @Override public void narrativePublished(Long workId, Long narrativePlanId) {
        planMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChapterPlanVersionEntity>()
                .eq(ChapterPlanVersionEntity::getWorkId, workId).eq(ChapterPlanVersionEntity::getCurrentMarker, 1)
                .eq(ChapterPlanVersionEntity::getDeleted, 0)).forEach(plan -> sourceChainService.markNeedsReview(
                        plan.getChapterId(), "narrative:" + narrativePlanId + ":" + plan.getChapterId(),
                        List.of("narrative_plan_changed")));
    }

    @Override public void scenePlanPublished(Long chapterId, Long scenePlanId) {
        sourceChainService.markNeedsReview(chapterId, "scene-plan:" + scenePlanId, List.of("scene_plan_changed"), false, false, true);
    }

    @Override public void scenePlanCreated(Long chapterId, Long scenePlanId) {
        scenePlanCreated(chapterId, scenePlanId, null);
    }

    @Override public void scenePlanCreated(Long chapterId, Long scenePlanId, Long contextSnapshotId) {
        ChapterPlanVersionEntity plan = planMapper.selectById(scenePlanId);
        if (plan == null) {
            return;
        }
        Long snapshotId = sourceChainService.recordSnapshot(plan.getWorkId(), chapterId, new AssetSourceView("scene_plan",
                plan.getId(), plan.getPlanNo(), null, "current", List.of(), null, plan.getNarrativePlanId(),
                plan.getOutlineId(), plan.getOutlineRevision(), plan.getId(), contextSnapshotId));
        plan.setSourceSnapshotId(snapshotId);
        plan.setValidityStatus("current");
        planMapper.updateById(plan);
    }

    @Override public void generationCreated(Long chapterId, Long generationId) {
        ChapterGenerationEntity generation = generationMapper.selectById(generationId);
        if (generation == null) {
            return;
        }
        Long snapshotId = sourceChainService.recordSnapshot(generation.getWorkId(), chapterId, new AssetSourceView("generation",
                generationId, generation.getVersion(), null, "current", List.of(), null, null, generation.getOutlineId(),
                generation.getOutlineRevision(), generation.getChapterPlanVersionId(), null));
        generation.setSourceSnapshotId(snapshotId);
        generation.setValidityStatus("current");
        generationMapper.updateById(generation);
    }
}
