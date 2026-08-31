package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-07-13
 * @description 映射章节共创会话消息数据。
 */
@Data
@TableName("chapter_conversation_messages")
public class ChapterConversationMessageEntity extends BaseEntity {

    private Long conversationId;

    private Long chapterId;

    private String messageRole;

    private String content;

    private String clientMessageId;

    private String generationStatus;

    private Long aiTaskId;

    private Long focusBriefId;

    private String focusDecisionKey;

    private Long referencedMessageId;

    private String interactionJson;

    private String interactionResponseJson;
}
