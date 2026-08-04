package com.dugnan.moqi.sourcechain;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.sourcechain.dto.ChapterAssetSourceChainModels.AssetSourceView;
import com.dugnan.moqi.sourcechain.dto.ChapterAssetSourceChainModels.ChapterAssetSourceChainView;
import com.dugnan.moqi.sourcechain.entity.ChapterAssetSourceSnapshotEntity;
import com.dugnan.moqi.sourcechain.entity.ChapterAssetValidityAuditEntity;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetValidityAuditMapper;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 实现章节资产来源快照登记、幂等失效传播和链路查询。
 */
@Service
public class ChapterAssetSourceChainServiceImpl implements ChapterAssetSourceChainService {
    private static final String CURRENT = "current";
    private static final String NEEDS_REVIEW = "needs_review";
    private final ChapterOutlineQueryMapper outlineMapper;
    private final ChapterPlanVersionMapper planMapper;
    private final ChapterGenerationMapper generationMapper;
    private final ChapterAssetSourceSnapshotMapper snapshotMapper;
    private final ChapterAssetValidityAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    public ChapterAssetSourceChainServiceImpl(ChapterOutlineQueryMapper outlineMapper,
            ChapterPlanVersionMapper planMapper, ChapterGenerationMapper generationMapper,
            ChapterAssetSourceSnapshotMapper snapshotMapper, ChapterAssetValidityAuditMapper auditMapper,
            ObjectMapper objectMapper) {
        this.outlineMapper = outlineMapper;
        this.planMapper = planMapper;
        this.generationMapper = generationMapper;
        this.snapshotMapper = snapshotMapper;
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChapterAssetSourceChainView getSourceChain(Long chapterId) {
        ChapterOutlineEntity outline = outlineMapper.findLatest(chapterId);
        ChapterPlanVersionEntity plan = planMapper.selectOne(new LambdaQueryWrapper<ChapterPlanVersionEntity>()
                .eq(ChapterPlanVersionEntity::getChapterId, chapterId).eq(ChapterPlanVersionEntity::getCurrentMarker, 1)
                .eq(ChapterPlanVersionEntity::getDeleted, 0));
        ChapterGenerationEntity generation = generationMapper.selectOne(new LambdaQueryWrapper<ChapterGenerationEntity>()
                .eq(ChapterGenerationEntity::getChapterId, chapterId).eq(ChapterGenerationEntity::getDeleted, 0)
                .orderByDesc(ChapterGenerationEntity::getId).last("LIMIT 1"));
        return new ChapterAssetSourceChainView(chapterId, outlineView(outline), planView(plan), generationView(generation));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public Long recordSnapshot(Long workId, Long chapterId, AssetSourceView source) {
        ChapterAssetSourceSnapshotEntity existing = snapshotMapper.selectOne(
                new LambdaQueryWrapper<ChapterAssetSourceSnapshotEntity>().eq(ChapterAssetSourceSnapshotEntity::getAssetType,
                        source.assetType()).eq(ChapterAssetSourceSnapshotEntity::getAssetId, source.assetId())
                        .eq(ChapterAssetSourceSnapshotEntity::getAssetVersion, source.assetVersion()));
        if (existing != null) {
            return existing.getId();
        }
        ChapterAssetSourceSnapshotEntity snapshot = new ChapterAssetSourceSnapshotEntity();
        snapshot.setAssetType(source.assetType());
        snapshot.setAssetId(source.assetId());
        snapshot.setAssetVersion(source.assetVersion());
        snapshot.setChapterId(chapterId);
        snapshot.setWorkId(workId);
        snapshot.setSourceConsensusVersionId(source.sourceConsensusId());
        snapshot.setSourceNarrativePlanVersionId(source.sourceNarrativePlanId());
        snapshot.setSourceOutlineId(source.sourceOutlineId());
        snapshot.setSourceOutlineRevision(source.sourceOutlineRevision());
        snapshot.setSourceScenePlanVersionId(source.sourceScenePlanVersionId());
        snapshot.setSourceContextSnapshotId(source.sourceContextSnapshotId());
        snapshot.setDeleted(0);
        snapshot.setVersion(0);
        snapshotMapper.insert(snapshot);
        return snapshot.getId();
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public void markNeedsReview(Long chapterId, String upstreamEventKey, List<String> reasonCodes) {
        markNeedsReview(chapterId, upstreamEventKey, reasonCodes, true, true, true);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public void markNeedsReview(Long chapterId, String upstreamEventKey, List<String> reasonCodes,
            boolean includeOutline, boolean includePlan, boolean includeGeneration) {
        String reasons = json(reasonCodes);
        ChapterAssetValidityAuditEntity existing = auditMapper.selectOne(
                new LambdaQueryWrapper<ChapterAssetValidityAuditEntity>()
                        .eq(ChapterAssetValidityAuditEntity::getEventKey, upstreamEventKey));
        if (existing != null) {
            return;
        }
        ChapterAssetValidityAuditEntity audit = new ChapterAssetValidityAuditEntity();
        audit.setChapterId(chapterId);
        audit.setAssetType("chapter");
        audit.setAssetId(chapterId);
        audit.setEventKey(upstreamEventKey);
        audit.setValidityStatus(NEEDS_REVIEW);
        audit.setReasonCodesJson(reasons);
        audit.setDeleted(0);
        audit.setVersion(0);
        auditMapper.insert(audit);
        if (includeOutline) { outlineMapper.update(null, new UpdateWrapper<ChapterOutlineEntity>().eq("chapter_id", chapterId).eq("deleted", 0).set("validity_status", NEEDS_REVIEW).set("validity_reason_codes_json", reasons).setSql("version = version + 1")); }
        if (includePlan) { planMapper.update(null, new UpdateWrapper<ChapterPlanVersionEntity>().eq("chapter_id", chapterId).eq("current_marker", 1).eq("deleted", 0).set("validity_status", NEEDS_REVIEW).set("validity_reason_codes_json", reasons).setSql("version = version + 1")); }
        if (includeGeneration) { generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>().eq("chapter_id", chapterId).eq("deleted", 0).set("validity_status", NEEDS_REVIEW).set("validity_reason_codes_json", reasons).setSql("version = version + 1")); }
    }

    private AssetSourceView outlineView(ChapterOutlineEntity entity) {
        return entity == null ? null : new AssetSourceView("outline", entity.getId(), entity.getRevision(),
                entity.getSourceSnapshotId(), status(entity.getValidityStatus()), reasons(entity.getValidityReasonCodesJson()), null, null, entity.getId(),
                entity.getRevision(), null, null);
    }

    private AssetSourceView planView(ChapterPlanVersionEntity entity) {
        ChapterOutlineEntity outline = entity == null ? null : outlineMapper.selectById(entity.getOutlineId());
        return entity == null ? null : new AssetSourceView("scene_plan", entity.getId(), entity.getPlanNo(),
                entity.getSourceSnapshotId(), status(entity.getValidityStatus()), reasons(entity.getValidityReasonCodesJson()),
                outline == null ? null : outline.getConfirmedBriefId(), entity.getNarrativePlanId(),
                entity.getOutlineId(), entity.getOutlineRevision(), entity.getId(), null);
    }

    private AssetSourceView generationView(ChapterGenerationEntity entity) {
        ChapterOutlineEntity outline = entity == null ? null : outlineMapper.selectById(entity.getOutlineId());
        return entity == null ? null : new AssetSourceView("generation", entity.getId(), entity.getVersion(),
                entity.getSourceSnapshotId(), status(entity.getValidityStatus()), reasons(entity.getValidityReasonCodesJson()),
                outline == null ? null : outline.getConfirmedBriefId(), null,
                entity.getOutlineId(), entity.getOutlineRevision(), entity.getChapterPlanVersionId(), null);
    }

    private String status(String value) {
        return value == null ? CURRENT : value;
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化来源失效原因", exception);
        }
    }

    private List<String> reasons(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            return List.of("legacy_reason_unreadable");
        }
    }
}
