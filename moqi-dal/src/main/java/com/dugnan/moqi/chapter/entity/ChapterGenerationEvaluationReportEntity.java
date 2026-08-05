package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 保存正文候选一致性评价的不可变输入和安全结果摘要。
 */
@Data
@TableName("chapter_generation_evaluation_reports")
public class ChapterGenerationEvaluationReportEntity extends BaseEntity {

    private Long workId;
    private Long chapterId;
    private Long generationId;
    private Long generationSceneId;
    private Long contextSnapshotId;
    private Long aiTaskId;
    private Long agentRunId;
    private String idempotencyKey;
    private String inputFingerprint;
    private String sourceSnapshotJson;
    private String reportStatus;
    private String conclusion;
    private String findingsJson;
    private String rulesetVersion;
    private String evaluatorVersion;
    private Integer revisionAttempt;
    private Long revisionCandidateId;
}
