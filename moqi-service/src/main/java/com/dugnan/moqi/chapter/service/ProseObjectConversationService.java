package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConversationDetail;

/**
 * @author dgn
 * @date 2026-08-26
 * @description 定义正式正文与候选对象独立会话的读取和幂等创建能力。
 */
public interface ProseObjectConversationService {

    /**
     * 查询正文对象当前活动会话，不存在时返回 null。
     *
     * @param chapterId 章节 ID
     * @param objectId 稳定正文对象 ID
     * @return 当前活动会话，不存在时返回 null
     */
    ConversationDetail get(Long chapterId, String objectId);

    /**
     * 在同一章节内幂等创建或复用正文对象活动会话。
     *
     * @param chapterId 章节 ID
     * @param objectId 稳定正文对象 ID
     * @return 已创建或复用的活动会话
     */
    ConversationDetail createOrGet(Long chapterId, String objectId);
}
