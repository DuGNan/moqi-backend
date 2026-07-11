package com.dugnan.moqi.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import com.dugnan.moqi.common.entity.BaseEntity;

@Getter
@Setter
@TableName("chapter_summaries")
public class ChapterSummaryEntity extends BaseEntity {

    private Long workId;

    private Long chapterId;

    private String summary;

    private String characterChangesJson;

    private String newSettingsJson;

    private String newForeshadowingJson;

    private String openQuestionsJson;

    private String summaryStatus;

    private Integer contentRevision;
}
