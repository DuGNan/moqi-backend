package com.dugnan.moqi.impact.entity;

import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @description 持久化经目标正文范围校验的事实变化证据。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("prose_revision_fact_changes")
public class ProseRevisionFactChangeEntity extends BaseEntity {
    private Long reportId;
    private String changeKey;
    private String factType;
    private String epistemicStatus;
    private String changeKind;
    private String impactScope;
    private String evidenceText;
    private Integer evidenceStartOffset;
    private Integer evidenceEndOffset;
    private BigDecimal confidence;
    private Integer directDependency;
    private String affectedChapterIdsJson;
    private String explanation;
}
