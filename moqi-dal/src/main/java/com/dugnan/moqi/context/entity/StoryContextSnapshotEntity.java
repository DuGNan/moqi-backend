package com.dugnan.moqi.context.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * 故事上下文快照持久化实体。
 *
 * @author dgn
 */
@Data
@TableName("story_context_snapshots")
public class StoryContextSnapshotEntity extends BaseEntity {

    private String scopeKey;

    private Long workId;

    private Long chapterId;

    private Long conversationId;

    private String profile;

    private Integer schemaVersion;

    private Long snapshotVersion;

    private Integer contextWindowTokens;

    private Integer outputReserveTokens;

    private Integer inputBudgetTokens;

    private Integer estimatedInputTokens;

    private String contentHash;

    private String snapshotJson;
}
