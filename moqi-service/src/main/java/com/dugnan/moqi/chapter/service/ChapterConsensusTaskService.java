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
}
