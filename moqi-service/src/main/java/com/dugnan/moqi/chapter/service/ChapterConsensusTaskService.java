package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConsensusTaskCreated;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConsensusTaskRequest;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 定义章节共识异步收束任务创建能力。
 */
public interface ChapterConsensusTaskService {

    /**
     * 创建章节共识收束任务。
     *
     * @param chapterId 章节 ID
     * @param request 任务请求
     * @return 已创建任务
     */
    ConsensusTaskCreated createTask(Long chapterId, ConsensusTaskRequest request);

    /**
     * 根据已通过成熟度检查的会话创建自动共识任务。
     *
     * @param chapterId 章节 ID
     * @param request 会话与基础 Brief 引用
     * @param lastMessageId 触发判断的助手消息 ID
     * @param idempotencyKey 自动收束幂等键
     * @return 已创建或复用的任务
     */
    ConsensusTaskCreated createAutoTask(Long chapterId, ConsensusTaskRequest request, Long lastMessageId,
            String evaluatorVersion, String idempotencyKey, java.util.List<Long> evidenceMessageIds,
            java.util.List<String> reasonCodes);
}
