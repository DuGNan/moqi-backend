package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dugnan.moqi.common.entity.BaseEntity;

@TableName("foreshadowing_items")
public class ForeshadowingItemEntity extends BaseEntity {

    private Long workId;

    private Long sourceChapterId;

    private String title;

    private String description;

    private String sourceText;

    private Integer sourceStartOffset;

    private Integer sourceEndOffset;

    private String status;

    private Long expectedPayoffChapterId;

    private Long actualPayoffChapterId;

    public Long getWorkId() {
        return workId;
    }

    public void setWorkId(Long workId) {
        this.workId = workId;
    }

    public Long getSourceChapterId() {
        return sourceChapterId;
    }

    public void setSourceChapterId(Long sourceChapterId) {
        this.sourceChapterId = sourceChapterId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSourceText() {
        return sourceText;
    }

    public void setSourceText(String sourceText) {
        this.sourceText = sourceText;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getExpectedPayoffChapterId() {
        return expectedPayoffChapterId;
    }

    public void setExpectedPayoffChapterId(Long expectedPayoffChapterId) {
        this.expectedPayoffChapterId = expectedPayoffChapterId;
    }

    public Long getActualPayoffChapterId() {
        return actualPayoffChapterId;
    }

    public void setActualPayoffChapterId(Long actualPayoffChapterId) {
        this.actualPayoffChapterId = actualPayoffChapterId;
    }
}
