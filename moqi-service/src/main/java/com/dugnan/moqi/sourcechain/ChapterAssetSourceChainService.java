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
    /**
     * 查询章节当前正式资产的来源链。
     * @param chapterId 章节编号
     * @return 来源链视图
     */
    ChapterAssetSourceChainView getSourceChain(Long chapterId);

    /**
     * 记录或复用同一资产版本的不可变来源快照。
     * @param workId 作品编号
     * @param chapterId 章节编号
     * @param source 来源资产
     * @return 快照编号
     */
    Long recordSnapshot(Long workId, Long chapterId, AssetSourceView source);

    /**
     * 将全部下游正式资产标记为需要复核。
     * @param chapterId 章节编号
     * @param upstreamEventKey 上游事件唯一键
     * @param reasonCodes 原因码
     */
    void markNeedsReview(Long chapterId, String upstreamEventKey, List<String> reasonCodes);

    /**
     * 按资产类型选择需要复核的下游，重复事件不重复更新。
     * @param chapterId 章节编号
     * @param upstreamEventKey 上游事件唯一键
     * @param reasonCodes 原因码
     * @param includeOutline 是否影响章纲
     * @param includePlan 是否影响场景规划
     * @param includeGeneration 是否影响正文批次
     */
    void markNeedsReview(Long chapterId, String upstreamEventKey, List<String> reasonCodes,
            boolean includeOutline, boolean includePlan, boolean includeGeneration);
}
