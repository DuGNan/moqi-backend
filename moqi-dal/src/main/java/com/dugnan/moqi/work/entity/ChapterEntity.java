package com.dugnan.moqi.work.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import com.dugnan.moqi.common.entity.BaseEntity;

@Getter
@Setter
@TableName("chapters")
public class ChapterEntity extends BaseEntity {

    private Long workId;

    private String title;

    private Integer chapterNo;

    private String chapterType;

    private String content;

    private String workflowStatus;
}
