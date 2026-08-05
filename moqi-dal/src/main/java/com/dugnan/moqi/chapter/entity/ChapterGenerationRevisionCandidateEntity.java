package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 保存仅供人工采纳的正文局部修订候选及其评价来源。
 */
@Data
@TableName("chapter_generation_revision_candidates")
public class ChapterGenerationRevisionCandidateEntity extends BaseEntity {

    private Long reportId;
    private Long generationId;
    private Long generationSceneId;
    private Long sourceSceneId;
    private String candidateStatus;
    private String revisionContent;
    private String evidenceRangeJson;
    private String sourceFingerprint;
}
