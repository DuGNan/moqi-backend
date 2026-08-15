package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 保存整章有界修订的冻结任务书、新候选和重新评价关联。
 */
@Data
@TableName("bounded_chapter_revisions")
public class BoundedChapterRevisionEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private Long sourceGenerationId;
    private Long sourceReportId;
    private Long resultGenerationId;
    private Long resultReportId;
    private Long aiTaskId;
    private Long agentRunId;
    private String idempotencyKey;
    private String revisionStatus;
    private String stopReason;
    private String findingKeysJson;
    private String revisionBriefJson;
    private String sourceContentHash;
    private String resultContentHash;
    private Long revisionModelCallId;
    private Integer revisionAttempt;
    private String errorCode;
    private String errorMessage;
}
