package com.dugnan.moqi.impact.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @description 持久化 Story Release、正文 revision 与已确认知识的来源映射。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("story_release_knowledge_sources")
public class StoryReleaseKnowledgeSourceEntity extends BaseEntity {
    private Long workId;
    private Long releaseId;
    private Long chapterId;
    private Long proseRevisionId;
    private String knowledgeType;
    private Long knowledgeId;
    private Long sourceCandidateId;
    private String sourceStatus;
    private Integer activeMarker;
}
