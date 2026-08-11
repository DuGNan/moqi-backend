package com.dugnan.moqi.planning.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 映射章节规划版本中的有序场景叶子节点。
 */
@Data
@TableName("scene_plan_versions")
public class ScenePlanVersionEntity extends BaseEntity {
    private Long chapterPlanVersionId;
    private String sceneKey;
    private Integer sequenceNo;
    private Integer contentSchemaVersion;
    private String contentJson;
}
