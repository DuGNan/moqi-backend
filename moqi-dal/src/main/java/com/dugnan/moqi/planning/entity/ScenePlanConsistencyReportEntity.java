package com.dugnan.moqi.planning.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 映射场景规划一致性检查的不可变输入和安全结果摘要。
 */
@Data
@TableName("scene_plan_consistency_reports")
public class ScenePlanConsistencyReportEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private Long chapterPlanVersionId;
    private Integer planVersion;
    private Long sourceSnapshotId;
    private Long aiTaskId;
    private Long agentRunId;
    private String idempotencyKey;
    private String inputFingerprint;
    private String planSnapshotJson;
    private String reportStatus;
    private String resultStatus;
    private String findingsJson;
    private String resolutionStatus;
    private String rulesetVersion;
    private String evaluatorVersion;
}
