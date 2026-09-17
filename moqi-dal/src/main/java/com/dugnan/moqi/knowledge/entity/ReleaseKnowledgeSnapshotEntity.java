package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-09-15
 * @description 保存作品发布版本对应的完整知识内容及历史封存状态。
 */
@Data
@TableName("story_release_knowledge_snapshots")
public class ReleaseKnowledgeSnapshotEntity extends BaseEntity {
    private Long workId;
    private Long releaseId;
    private String snapshotJson;
    private Integer sealed;
}
