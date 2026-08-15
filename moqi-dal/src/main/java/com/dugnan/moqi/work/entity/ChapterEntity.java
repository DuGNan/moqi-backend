package com.dugnan.moqi.work.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射作品章节基础数据。
 */
@Data
@TableName("chapters")
public class ChapterEntity extends BaseEntity {

    private Long workId;

    private String title;

    private Integer chapterNo;

    private String chapterType;

    private String content;

    private Long currentProseRevisionId;

    private String workflowStatus;
}
