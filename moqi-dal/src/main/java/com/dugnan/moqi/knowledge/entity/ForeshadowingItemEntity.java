package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射作品伏笔数据。
 */
@Data
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
}
