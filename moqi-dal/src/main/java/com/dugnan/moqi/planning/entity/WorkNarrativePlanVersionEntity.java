package com.dugnan.moqi.planning.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 映射作品级叙事规划的版本化事实。
 */
@Data
@TableName("work_narrative_plan_versions")
public class WorkNarrativePlanVersionEntity extends BaseEntity {
    private Long workId;
    private Integer planNo;
    private String planStatus;
    private String contentJson;
    private String sourceType;
    private String createdBy;
    private String publishedBy;
    private Integer currentMarker;
}
