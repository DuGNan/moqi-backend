package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 映射一次基于已采纳正文冻结快照的故事知识提取批次。
 */
@Data
@TableName("story_knowledge_extraction_batches")
public class StoryKnowledgeExtractionBatchEntity extends BaseEntity {

    private Long workId;
    private Long chapterId;
    private Long generationId;
    private Long sourceProseRevisionId;
    private Long sourceStoryReleaseId;
    private Long aiTaskId;
    private Long agentRunId;
    private String extractorVersion;
    private String idempotencyKey;
    private Integer sourceContentRevision;
    private String sourceFingerprint;
    private String sourceContent;
    private String batchStatus;
    private Integer candidateCount;
    private String errorCode;
}
