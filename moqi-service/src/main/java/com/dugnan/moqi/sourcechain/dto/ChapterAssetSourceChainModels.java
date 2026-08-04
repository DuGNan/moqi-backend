package com.dugnan.moqi.sourcechain.dto;

import java.util.List;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 定义章节资产来源链的公开查询数据契约。
 */
public final class ChapterAssetSourceChainModels {
    private ChapterAssetSourceChainModels() {
    }

    public record AssetSourceView(String assetType, Long assetId, Integer assetVersion,
            Long sourceSnapshotId, String validityStatus, List<String> reasonCodes,
            Long sourceConsensusId, Long sourceNarrativePlanId, Long sourceOutlineId,
            Integer sourceOutlineRevision, Long sourceScenePlanVersionId, Long sourceContextSnapshotId) {
        public AssetSourceView {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }

    public record ChapterAssetSourceChainView(Long chapterId, AssetSourceView outline,
            AssetSourceView currentScenePlan, AssetSourceView latestGeneration) {
    }
}
