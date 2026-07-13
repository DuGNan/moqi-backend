package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射待确认设定候选数据。
 */
@Data
@TableName("setting_candidates")
public class SettingCandidateEntity extends BaseEntity {

    private Long workId;

    private Long chapterId;

    private String sourceType;

    private Long sourceId;

    private Integer sourceContentRevision;

    private Integer sourceStartOffset;

    private Integer sourceEndOffset;

    private String settingType;

    private String name;

    private String content;

    private String candidateStatus;

    private Long confirmedSettingId;
}
