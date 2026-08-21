package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 保存统一章节正文工作区中稳定、可变的正文候选。
 */
@Data
@TableName("chapter_prose_candidates")
public class ChapterProseCandidateEntity extends BaseEntity {

    private Long workId;
    private Long chapterId;
    private Long rootCandidateId;
    private Long parentCandidateId;
    private String sourceKind;
    private Long sourceGenerationId;
    private Long sourceBoundedRevisionId;
    private Long qualityGenerationId;
    private String qualityRequestStatus;
    private String candidateStatus;
    private String adoptionStatus;
    private String content;
    private String contentHash;
    private Integer wordCount;
}
