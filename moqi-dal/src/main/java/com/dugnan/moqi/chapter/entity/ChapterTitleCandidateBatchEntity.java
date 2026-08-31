package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 保存章节 AI 取名批次的冻结正文、运行状态和溯源信息。
 */
@Data
@TableName("chapter_title_candidate_batches")
public class ChapterTitleCandidateBatchEntity extends BaseEntity {

    private Long workId;
    private Long chapterId;
    private Long aiTaskId;
    private Long agentRunId;
    private String idempotencyKey;
    private String batchStatus;
    private String sourceKind;
    private String sourceObjectId;
    private Long sourceCandidateId;
    private Integer sourceVersion;
    private String sourceContentHash;
    private String sourceContentSnapshot;
    private String promptContent;
    private String inputFingerprint;
    private String promptTemplateVersion;
    private Integer currentAttempt;
    private String errorCode;
    private String errorMessage;
}
