package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射章节简报数据。
 */
@Data
@TableName("chapter_briefs")
public class ChapterBriefEntity extends BaseEntity {

    private Long workId;

    private Long chapterId;

    private String briefStatus;

    private String triggerSource;

    private Long baseBriefId;

    private String sourceAssetType;

    private Long sourceAssetId;

    private Long sourceReportId;

    private String idempotencyKey;

    private String briefContent;
}
