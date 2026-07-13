/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射章节生成记录数据。
 */
package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import com.dugnan.moqi.common.entity.BaseEntity;

@Getter
@Setter
@TableName("chapter_generations")
public class ChapterGenerationEntity extends BaseEntity {

    private Long workId;

    private Long chapterId;

    private Long briefId;

    private String generationStatus;

    private String generatedContent;
}
