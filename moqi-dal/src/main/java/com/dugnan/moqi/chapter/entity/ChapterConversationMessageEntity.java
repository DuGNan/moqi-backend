package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import com.dugnan.moqi.common.entity.BaseEntity;

@Getter
@Setter
@TableName("chapter_conversation_messages")
public class ChapterConversationMessageEntity extends BaseEntity {

    private Long conversationId;

    private Long chapterId;

    private String messageRole;

    private String content;
}
