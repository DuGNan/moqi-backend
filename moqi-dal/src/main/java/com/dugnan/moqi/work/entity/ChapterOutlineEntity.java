/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射章节大纲修订数据。
 */
package com.dugnan.moqi.work.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import com.dugnan.moqi.common.entity.BaseEntity;

@Getter
@Setter
@TableName("chapter_outlines")
public class ChapterOutlineEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private String outlineStatus;
    private Integer revision;
}
