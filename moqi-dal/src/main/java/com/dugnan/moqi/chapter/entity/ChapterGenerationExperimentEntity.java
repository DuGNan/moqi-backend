package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-07
 * @description 映射不进入正式候选流程的章节生成策略实验记录。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("chapter_generation_experiments")
public class ChapterGenerationExperimentEntity extends BaseEntity {

    private Long workId;
    private Long chapterId;
    private Long chapterPlanVersionId;
    private String experimentGroupKey;
    private String strategy;
    private Integer sampleNo;
    private String experimentStatus;
    private String templateVersion;
    private String inputFingerprint;
    private String provider;
    private String model;
    private Integer configVersion;
    private Integer credentialVersion;
    private String sceneRouteJson;
    private String modelCallIdsJson;
    private String rawSceneOutputsJson;
    private String generatedContent;
    private Integer wordCount;
    private Long elapsedMillis;
    private String errorMessage;
}
