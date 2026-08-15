package com.dugnan.moqi.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 映射固定 Story Release 基线的作品修订工作区。
 */
@Data
@TableName("work_revision_workspaces")
public class WorkRevisionWorkspaceEntity extends BaseEntity {
    private Long workId;
    private Long baselineReleaseId;
    private Long publishedReleaseId;
    private Integer baselineWorkVersion;
    private String workspaceStatus;
    private Integer currentMarker;
    private String blockingItemsJson;
    private String idempotencyKey;
    private String createdBy;
    private String abandonedBy;
}
