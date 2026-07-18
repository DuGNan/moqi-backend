package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射章节生成记录数据。
 */
@Data
@TableName("chapter_generations")
public class ChapterGenerationEntity extends BaseEntity {

    private Long workId;

    private Long chapterId;

    private Long briefId;

    private Long outlineId;

    private Integer outlineRevision;

    private String generationStatus;

    private String generationMode;

    private String lengthPreset;

    private Integer customWordCount;

    private String basisSnapshotJson;

    private String generatedContent;

    private Integer wordCount;

    private Long aiTaskId;
}
