package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.dto.BoundedChapterRevisionModels.BoundedRevisionView;
import com.dugnan.moqi.chapter.dto.BoundedChapterRevisionModels.CreateBoundedRevisionRequest;
import com.dugnan.moqi.chapter.dto.BoundedChapterRevisionModels.RetryBoundedRevisionRequest;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 定义整章评价 finding 到一次有界修订候选的业务边界。
 */
public interface BoundedChapterRevisionService {
    /**
     * 根据整章评价创建或幂等复用有界修订任务。
     *
     * @param chapterId 章节 ID
     * @param generationId 来源正文候选 ID
     * @param request 创建请求
     * @return 有界修订视图
     */
    BoundedRevisionView create(Long chapterId, Long generationId, CreateBoundedRevisionRequest request);

    /**
     * 查询有界修订及重新评价恢复状态。
     *
     * @param chapterId 章节 ID
     * @param generationId 来源正文候选 ID
     * @param revisionId 有界修订 ID
     * @return 有界修订视图
     */
    BoundedRevisionView get(Long chapterId, Long generationId, Long revisionId);

    /**
     * 重试失败的修订或重新评价启动步骤。
     *
     * @param chapterId 章节 ID
     * @param generationId 来源正文候选 ID
     * @param revisionId 有界修订 ID
     * @param request 重试请求
     * @return Agent Run 当前视图
     */
    AgentRunView retry(Long chapterId, Long generationId, Long revisionId, RetryBoundedRevisionRequest request);

    /**
     * 放弃尚未终结的有界修订。
     *
     * @param chapterId 章节 ID
     * @param generationId 来源正文候选 ID
     * @param revisionId 有界修订 ID
     * @return Agent Run 当前视图
     */
    AgentRunView cancel(Long chapterId, Long generationId, Long revisionId);
}
