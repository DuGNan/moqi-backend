package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dugnan.moqi.common.entity.BaseEntity;

@TableName("chapter_conversations")
public class ChapterConversationEntity extends BaseEntity {

    private Long workId;

    private Long chapterId;

    private String conversationStatus;

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

    public String getConversationStatus() {
        return conversationStatus;
    }

    public void setConversationStatus(String conversationStatus) {
        this.conversationStatus = conversationStatus;
    }
}
