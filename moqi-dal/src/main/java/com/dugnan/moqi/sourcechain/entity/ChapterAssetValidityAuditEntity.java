package com.dugnan.moqi.sourcechain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 记录章节资产有效性变更事件以保证传播幂等。
 */
@Data
@TableName("chapter_asset_validity_audits")
public class ChapterAssetValidityAuditEntity extends BaseEntity {
    private Long chapterId;
    private String assetType;
    private Long assetId;
    private Long sourceSnapshotId;
    private String eventKey;
    private String validityStatus;
    private String reasonCodesJson;
}
