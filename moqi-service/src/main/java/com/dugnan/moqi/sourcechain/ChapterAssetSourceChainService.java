package com.dugnan.moqi.sourcechain;

import java.util.List;

import com.dugnan.moqi.sourcechain.dto.ChapterAssetSourceChainModels.AssetSourceView;
import com.dugnan.moqi.sourcechain.dto.ChapterAssetSourceChainModels.ChapterAssetSourceChainView;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 定义章节资产来源快照和有效性链的查询及显式传播边界。
 */
public interface ChapterAssetSourceChainService {
    ChapterAssetSourceChainView getSourceChain(Long chapterId);

    Long recordSnapshot(Long workId, Long chapterId, AssetSourceView source);

    void markNeedsReview(Long chapterId, String upstreamEventKey, List<String> reasonCodes);

    void markNeedsReview(Long chapterId, String upstreamEventKey, List<String> reasonCodes,
            boolean includeOutline, boolean includePlan, boolean includeGeneration);
}
