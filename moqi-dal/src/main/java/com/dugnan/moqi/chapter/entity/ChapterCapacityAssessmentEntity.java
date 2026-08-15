package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 持久化章节容量评估的冻结输入、运行状态和候选结果。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("chapter_capacity_assessments")
public class ChapterCapacityAssessmentEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private Long chapterPlanVersionId;
    private Integer scenePlanNo;
    private Integer targetWordCount;
    private String lengthPreset;
    private Integer customWordCount;
    private String idempotencyKey;
    private String inputFingerprint;
    private String briefTemplateVersion;
    private String briefFingerprint;
    private String sourceSnapshotJson;
    private String assessmentStatus;
    private String resultJson;
    private String evaluatorVersion;
    private Long aiTaskId;
    private Long agentRunId;
    private Long modelCallId;
    private String errorCode;
    private String errorMessage;
}
