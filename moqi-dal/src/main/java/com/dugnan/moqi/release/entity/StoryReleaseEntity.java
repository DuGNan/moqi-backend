package com.dugnan.moqi.release.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 映射作品级不可变 Story Release 和原子切换审计信息。
 */
@Data
@TableName("story_releases")
public class StoryReleaseEntity extends BaseEntity {
    private Long workId;
    private Long parentReleaseId;
    private Long rollbackOfReleaseId;
    private Integer releaseNo;
    private String releaseStatus;
    private Integer currentMarker;
    private String releaseHash;
    private String idempotencyKey;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
}
