package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射章节关键事件数据。
 */
@Data
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
}
