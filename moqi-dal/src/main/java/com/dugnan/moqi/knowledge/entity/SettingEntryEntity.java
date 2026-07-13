package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射已确认作品设定数据。
 */
@Data
@TableName("setting_entries")
public class SettingEntryEntity extends BaseEntity {

    private Long workId;

    private String settingType;

    private String name;

    private String aliasesJson;

    private String content;

    private String attributesJson;

    private Long sourceChapterId;

    private Long sourceCandidateId;

    private String entryStatus;
}
