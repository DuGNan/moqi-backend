package com.dugnan.moqi.work.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dugnan.moqi.common.entity.BaseEntity;

@TableName("chapter_outlines")
public class ChapterOutlineEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private String outlineStatus;
    private Integer revision;

    public Long getWorkId() { return workId; }
    public void setWorkId(Long workId) { this.workId = workId; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public String getOutlineStatus() { return outlineStatus; }
    public void setOutlineStatus(String outlineStatus) { this.outlineStatus = outlineStatus; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
}
