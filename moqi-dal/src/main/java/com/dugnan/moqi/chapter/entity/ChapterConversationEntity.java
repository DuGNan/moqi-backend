/**
 * @author dgn
 * @date:2026-07-13
 * @description:映射章节共创会话数据。
 */
package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import com.dugnan.moqi.common.entity.BaseEntity;

@Getter
@Setter
@TableName("chapter_conversations")
public class ChapterConversationEntity extends BaseEntity {

    private Long workId;

    private Long chapterId;

    private String conversationStatus;
}
