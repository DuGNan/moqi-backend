package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dugnan.moqi.common.entity.BaseEntity;

@TableName("chapter_briefs")
public class ChapterBriefEntity extends BaseEntity {

    private Long workId;

    private Long chapterId;

    private String briefStatus;

    private String briefContent;

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

    public String getBriefStatus() {
        return briefStatus;
    }

    public void setBriefStatus(String briefStatus) {
        this.briefStatus = briefStatus;
    }

    public String getBriefContent() {
        return briefContent;
    }

    public void setBriefContent(String briefContent) {
        this.briefContent = briefContent;
    }
}
