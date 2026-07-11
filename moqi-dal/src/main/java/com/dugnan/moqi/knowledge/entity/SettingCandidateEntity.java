package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dugnan.moqi.common.entity.BaseEntity;

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

    public Long getWorkId() {
        return workId;
    }

    public void setWorkId(Long workId) {
        this.workId = workId;
    }

    public Long getChapterId() {
        return chapterId;
    }

    public void setChapterId(Long chapterId) {
        this.chapterId = chapterId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public Integer getSourceContentRevision() {
        return sourceContentRevision;
    }

    public void setSourceContentRevision(Integer sourceContentRevision) {
        this.sourceContentRevision = sourceContentRevision;
    }

    public Integer getSourceStartOffset() {
        return sourceStartOffset;
    }

    public void setSourceStartOffset(Integer sourceStartOffset) {
        this.sourceStartOffset = sourceStartOffset;
    }

    public Integer getSourceEndOffset() {
        return sourceEndOffset;
    }

    public void setSourceEndOffset(Integer sourceEndOffset) {
        this.sourceEndOffset = sourceEndOffset;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCandidateStatus() {
        return candidateStatus;
    }

    public void setCandidateStatus(String candidateStatus) {
        this.candidateStatus = candidateStatus;
    }

    public Long getConfirmedSettingId() {
        return confirmedSettingId;
    }

    public void setConfirmedSettingId(Long confirmedSettingId) {
        this.confirmedSettingId = confirmedSettingId;
    }
}
