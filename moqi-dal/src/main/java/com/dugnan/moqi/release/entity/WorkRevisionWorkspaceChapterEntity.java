package com.dugnan.moqi.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 映射作品修订工作区中待发布章节及其冻结基线。
 */
@Data
@TableName("work_revision_workspace_chapters")
public class WorkRevisionWorkspaceChapterEntity extends BaseEntity {
    private Long workspaceId;
    private Long workId;
    private Long chapterId;
    private Long proseRevisionId;
    private Long baselineProseRevisionId;
    private Integer baselineChapterVersion;
    private String entryStatus;
}
