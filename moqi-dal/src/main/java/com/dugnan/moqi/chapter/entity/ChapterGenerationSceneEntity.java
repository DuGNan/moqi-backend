package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 映射一个章节生成批次中的场景候选正文与可恢复状态。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("chapter_generation_scenes")
public class ChapterGenerationSceneEntity extends BaseEntity {

    private Long generationId;
    private Long scenePlanVersionId;
    private String sceneKey;
    private Integer sequenceNo;
    private Long contextSnapshotId;
    private String promptTemplateVersion;
    private String sceneStatus;
    private String generatedContent;
    private String contentHash;
    private Integer wordCount;
    private Long sourceSceneDraftId;
    private Long modelCallId;
    private String finishReason;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Long elapsedMillis;
}
