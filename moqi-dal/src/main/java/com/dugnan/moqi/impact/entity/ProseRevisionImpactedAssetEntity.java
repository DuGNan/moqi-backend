package com.dugnan.moqi.impact.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @description 持久化影响报告解析出的真实下游资产。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("prose_revision_impacted_assets")
public class ProseRevisionImpactedAssetEntity extends BaseEntity {
    private Long reportId;
    private Long chapterId;
    private String assetType;
    private Long assetId;
    private String dependencyType;
    private String validityStatus;
    private String reasonCode;
}
