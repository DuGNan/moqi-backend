package com.dugnan.moqi.impact.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @description 持久化正文 revision 影响报告及其可恢复运行身份。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("prose_revision_impact_reports")
public class ProseRevisionImpactReportEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private Long workspaceId;
    private Long baselineRevisionId;
    private Long targetRevisionId;
    private Long baselineReleaseId;
    private Long agentRunId;
    private Long modelCallId;
    private String idempotencyKey;
    private String inputFingerprint;
    private String sourceGraphFingerprint;
    private String analyzerVersion;
    private String reportStatus;
    private String impactScope;
    private Integer blocking;
    private String summaryJson;
    private String errorCode;
}
