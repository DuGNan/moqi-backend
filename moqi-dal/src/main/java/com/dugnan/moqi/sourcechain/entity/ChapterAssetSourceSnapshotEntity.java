package com.dugnan.moqi.sourcechain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 保存章节资产在创建时消费的不可变来源版本快照。
 */
@Data
@TableName("chapter_asset_source_snapshots")
public class ChapterAssetSourceSnapshotEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private String assetType;
    private Long assetId;
    private Integer assetVersion;
    private Long sourceConsensusVersionId;
    private Long sourceNarrativePlanVersionId;
    private Long sourceOutlineId;
    private Integer sourceOutlineRevision;
    private Long sourceScenePlanVersionId;
    private Long sourceContextSnapshotId;
    private String sourceContentHash;
}
