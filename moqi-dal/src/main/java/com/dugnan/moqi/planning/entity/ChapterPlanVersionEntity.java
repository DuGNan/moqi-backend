package com.dugnan.moqi.planning.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 映射章节场景规划候选及已发布版本。
 */
@Data
@TableName("chapter_plan_versions")
public class ChapterPlanVersionEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private Integer planNo;
    private Long narrativePlanId;
    private Integer narrativePlanNo;
    private Long outlineId;
    private Integer outlineRevision;
    private Long agentRunId;
    private Long aiTaskId;
    private String planStatus;
    private String contentJson;
    private String sourceType;
    private String createdBy;
    private String publishedBy;
    private Integer currentMarker;
}
