/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射待确认设定候选数据。
 */
package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import com.dugnan.moqi.common.entity.BaseEntity;

@Getter
@Setter
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
