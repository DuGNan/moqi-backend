package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-07-16
 * @description 映射 AI 异步任务数据。
 */
@Data
@TableName("ai_tasks")
public class AiTaskEntity extends BaseEntity {

    private String taskType;

    private String taskStatus;

    private Long workId;

    private Long chapterId;

    private Long resultMessageId;

    private Long resultGenerationId;

    private Long resultSuggestionId;

    private Long contextSnapshotId;

    private String errorCode;

    private String errorMessage;
}
