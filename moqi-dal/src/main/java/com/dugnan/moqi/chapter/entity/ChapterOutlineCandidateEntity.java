package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 映射章节大纲调整候选及其确认状态。
 */
@Data
@TableName("chapter_outline_candidates")
public class ChapterOutlineCandidateEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private Long conversationId;
    private Long aiTaskId;
    private Long confirmedBriefId;
    private String candidateType;
    private String idempotencyKey;
    private Long baseOutlineId;
    private Integer baseOutlineRevision;
    private String baseOutlineContent;
    private String candidateStatus;
    private String adjustmentInstruction;
    private String candidateContent;
    private String diffJson;
    private String consensusImpactJson;
    private Integer contentSchemaVersion;
    private String migrationReviewStatus;
    private String migrationReasonCodesJson;
    private Long resultOutlineId;
    private Integer resultOutlineRevision;
}
