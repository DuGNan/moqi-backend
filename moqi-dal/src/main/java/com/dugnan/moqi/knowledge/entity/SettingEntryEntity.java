package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import com.dugnan.moqi.common.entity.BaseEntity;

@Getter
@Setter
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
