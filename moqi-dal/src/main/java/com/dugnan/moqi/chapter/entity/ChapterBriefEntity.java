package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import com.dugnan.moqi.common.entity.BaseEntity;

@Getter
@Setter
@TableName("chapter_briefs")
public class ChapterBriefEntity extends BaseEntity {

    private Long workId;

    private Long chapterId;

    private String briefStatus;

    private String briefContent;
}
