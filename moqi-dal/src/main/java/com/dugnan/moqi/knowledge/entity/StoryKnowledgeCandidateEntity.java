package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 映射等待用户确认的摘要、事件、设定或伏笔知识候选。
 */
@Data
@TableName("story_knowledge_candidates")
public class StoryKnowledgeCandidateEntity extends BaseEntity {

    private Long batchId;
    private Long workId;
    private Long chapterId;
    private Long generationId;
    private String candidateKey;
    private String candidateType;
    private String candidateStatus;
    private String payloadJson;
    private Integer evidenceStartOffset;
    private Integer evidenceEndOffset;
    private String evidenceText;
    private String candidateFingerprint;
    private Long conflictTargetId;
    private String confirmedTargetType;
    private Long confirmedTargetId;
}
