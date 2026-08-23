package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-23
 * @description 冻结正文候选采纳输入、门禁结果及发布链路恢复引用。
 */
@Data
@TableName("chapter_prose_candidate_adoptions")
public class ProseCandidateAdoptionEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private Long candidateId;
    private Integer candidateVersion;
    private String candidateContentHash;
    private Integer expectedFormalVersion;
    private Long qualityReportId;
    private String idempotencyKey;
    private String adoptionMode;
    private String adoptionStatus;
    private Integer formalResultVersion;
    private String formalResultHash;
    private Long revisionId;
    private Long workspaceId;
    private Long impactReportId;
    private String errorCode;
}
