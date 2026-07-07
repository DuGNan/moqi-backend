package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dugnan.moqi.common.entity.BaseEntity;

@TableName("chapter_key_events")
public class ChapterKeyEventEntity extends BaseEntity {

    private Long workId;

    private Long chapterId;

    private String eventTitle;

    private String eventContent;

    private String eventType;

    private Integer occurredOrder;

    private String relatedSettingIdsJson;

    private String relatedForeshadowingIdsJson;

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

    public String getEventTitle() {
        return eventTitle;
    }

    public void setEventTitle(String eventTitle) {
        this.eventTitle = eventTitle;
    }

    public String getEventContent() {
        return eventContent;
    }

    public void setEventContent(String eventContent) {
        this.eventContent = eventContent;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Integer getOccurredOrder() {
        return occurredOrder;
    }

    public void setOccurredOrder(Integer occurredOrder) {
        this.occurredOrder = occurredOrder;
    }

    public String getRelatedSettingIdsJson() {
        return relatedSettingIdsJson;
    }

    public void setRelatedSettingIdsJson(String relatedSettingIdsJson) {
        this.relatedSettingIdsJson = relatedSettingIdsJson;
    }

    public String getRelatedForeshadowingIdsJson() {
        return relatedForeshadowingIdsJson;
    }

    public void setRelatedForeshadowingIdsJson(String relatedForeshadowingIdsJson) {
        this.relatedForeshadowingIdsJson = relatedForeshadowingIdsJson;
    }
}
