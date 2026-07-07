package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dugnan.moqi.common.entity.BaseEntity;

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

    public Long getWorkId() {
        return workId;
    }

    public void setWorkId(Long workId) {
        this.workId = workId;
    }

    public String getSettingType() {
        return settingType;
    }

    public void setSettingType(String settingType) {
        this.settingType = settingType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAliasesJson() {
        return aliasesJson;
    }

    public void setAliasesJson(String aliasesJson) {
        this.aliasesJson = aliasesJson;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAttributesJson() {
        return attributesJson;
    }

    public void setAttributesJson(String attributesJson) {
        this.attributesJson = attributesJson;
    }

    public Long getSourceChapterId() {
        return sourceChapterId;
    }

    public void setSourceChapterId(Long sourceChapterId) {
        this.sourceChapterId = sourceChapterId;
    }

    public Long getSourceCandidateId() {
        return sourceCandidateId;
    }

    public void setSourceCandidateId(Long sourceCandidateId) {
        this.sourceCandidateId = sourceCandidateId;
    }

    public String getEntryStatus() {
        return entryStatus;
    }

    public void setEntryStatus(String entryStatus) {
        this.entryStatus = entryStatus;
    }
}
