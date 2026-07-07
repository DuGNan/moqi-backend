package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dugnan.moqi.common.entity.BaseEntity;

@TableName("chapter_summaries")
public class ChapterSummaryEntity extends BaseEntity {

    private Long workId;

    private Long chapterId;

    private String summary;

    private String characterChangesJson;

    private String newSettingsJson;

    private String newForeshadowingJson;

    private String openQuestionsJson;

    private String summaryStatus;

    private Integer contentRevision;

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

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getCharacterChangesJson() {
        return characterChangesJson;
    }

    public void setCharacterChangesJson(String characterChangesJson) {
        this.characterChangesJson = characterChangesJson;
    }

    public String getNewSettingsJson() {
        return newSettingsJson;
    }

    public void setNewSettingsJson(String newSettingsJson) {
        this.newSettingsJson = newSettingsJson;
    }

    public String getNewForeshadowingJson() {
        return newForeshadowingJson;
    }

    public void setNewForeshadowingJson(String newForeshadowingJson) {
        this.newForeshadowingJson = newForeshadowingJson;
    }

    public String getOpenQuestionsJson() {
        return openQuestionsJson;
    }

    public void setOpenQuestionsJson(String openQuestionsJson) {
        this.openQuestionsJson = openQuestionsJson;
    }

    public String getSummaryStatus() {
        return summaryStatus;
    }

    public void setSummaryStatus(String summaryStatus) {
        this.summaryStatus = summaryStatus;
    }

    public Integer getContentRevision() {
        return contentRevision;
    }

    public void setContentRevision(Integer contentRevision) {
        this.contentRevision = contentRevision;
    }
}
