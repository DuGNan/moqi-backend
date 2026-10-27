package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConversationDetail;

/**
 * @author dgn
 * @date 2026-08-26
 * @description 定义正式正文与候选对象独立会话的读取和幂等创建能力。
 */
public interface ProseObjectConversationService {

    /**
     * 读取指定正文对象的历史会话。
     * @param chapterId 章节 ID
     * @param objectId 正文对象 ID
     * @return 按创建顺序倒序排列的会话
     */
    java.util.List<ConversationDetail> history(Long chapterId, String objectId);

    /**
     * 归档旧会话并幂等创建新会话。
     * @param chapterId 章节 ID
     * @param objectId 正文对象 ID
     * @param expectedConversationId 被归档的旧会话 ID
     * @return 当前活动会话
     */
    ConversationDetail restart(Long chapterId, String objectId, Long expectedConversationId);

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
