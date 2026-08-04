package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 映射章节共识分流后待确认的作用域候选。
 */
@Data
@TableName("chapter_consensus_scope_candidates")
public class ChapterConsensusScopeCandidateEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private Long briefId;
    private Long taskId;
    private String scope;
    private String targetRefJson;
    private String sourceMessageIdsJson;
    private String baseResourceType;
    private Long baseVersionId;
    private Integer baseVersion;
    private String candidateContentJson;
    private java.math.BigDecimal confidence;
    private String candidateStatus;
    private String idempotencyKey;
}
