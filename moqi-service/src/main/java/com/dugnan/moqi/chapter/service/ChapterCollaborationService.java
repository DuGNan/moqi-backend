package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.BriefDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.BriefRequest;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConversationDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageCreated;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageList;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineRequest;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.SendMessageRequest;

/**
 * @author dgn
 * @date 2026-07-16
 * @description 定义章节共创、简报和大纲业务能力。
 */
public interface ChapterCollaborationService {

    /**
     * 查询章节当前活动会话。
     *
     * @param chapterId 章节 ID
     * @return 会话详情
     */
    ConversationDetail getConversation(Long chapterId);

    /**
     * 创建或复用章节活动会话。
     *
     * @param chapterId 章节 ID
     * @return 会话详情
     */
    ConversationDetail createOrGetConversation(Long chapterId);

    /**
     * 查询会话消息列表。
     *
     * @param conversationId 会话 ID
     * @return 消息列表
     */
    MessageList listMessages(Long conversationId);

    /**
     * 发送会话消息。
     *
     * @param conversationId 会话 ID
     * @param request 发送请求
     * @return 已创建消息
     */
    MessageCreated sendMessage(Long conversationId, SendMessageRequest request);

    /**
     * 查询章节最新 brief。
     *
     * @param chapterId 章节 ID
     * @return brief 详情
     */
    BriefDetail getLatestBrief(Long chapterId);

    /**
     * 保存章节最新 brief。
     *
     * @param chapterId 章节 ID
     * @param request brief 请求
     * @return brief 详情
     */
    BriefDetail saveLatestBrief(Long chapterId, BriefRequest request);

    /**
     * 查询章节正式大纲。
     *
     * @param chapterId 章节 ID
     * @return 大纲详情
     */
    OutlineDetail getOutline(Long chapterId);

    /**
     * 保存章节正式大纲。
     *
     * @param chapterId 章节 ID
     * @param request 大纲请求
     * @return 大纲详情
     */
    OutlineDetail saveOutline(Long chapterId, OutlineRequest request);

}
