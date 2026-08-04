package com.dugnan.moqi.work.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射章节大纲修订数据。
 */
@Data
@TableName("chapter_outlines")
public class ChapterOutlineEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private Long confirmedBriefId;
    private String outlineStatus;
    private String outlineContent;
    private Integer revision;
    private Integer contentSchemaVersion;
    private String migrationReviewStatus;
    private String migrationReasonCodesJson;
    private Long sourceSnapshotId;
    private String validityStatus;
    private String validityReasonCodesJson;
}
