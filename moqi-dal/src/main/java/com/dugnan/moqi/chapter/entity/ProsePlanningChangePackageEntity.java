package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 保存正文修改建议关联的待确认权威场景规划变更包。
 */
@Data
@TableName("prose_planning_change_packages")
public class ProsePlanningChangePackageEntity extends BaseEntity {

    private Long workId;
    private Long chapterId;
    private Long assistanceId;
    private Long targetCandidateId;
    private String idempotencyKey;
    private String packageStatus;
    private String changeSummary;
    private String beforeSummary;
    private String afterSummary;
    private Integer targetCandidateVersion;
    private String targetCandidateHash;
    private Long baseOutlineId;
    private Integer baseOutlineRevision;
    private Integer baseOutlineVersion;
    private Long baseScenePlanId;
    private Integer baseScenePlanVersion;
    private String proposedScenesJson;
    private Integer appliedCandidateVersion;
    private String appliedCandidateHash;
    private Long resultScenePlanId;
}
