package com.dugnan.moqi.chapter.title;

import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.AdoptRequest;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.AdoptedTitleView;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.BatchView;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.CreateBatchRequest;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.LatestBatchView;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.RetryRequest;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 定义章节 AI 取名批次、恢复和采用的业务边界。
 */
public interface ChapterTitleCandidateService {

    /**
     * 基于已保存正文创建可恢复的标题候选批次。
     *
     * @param chapterId 章节 ID
     * @param request 创建请求
     * @return 批次公开视图
     */
    BatchView create(Long chapterId, CreateBatchRequest request);

    /**
     * 恢复指定正文对象最近创建的标题候选批次。
     *
     * @param chapterId 章节 ID
     * @param sourceKind 正文来源类型
     * @param sourceObjectId 正文对象 ID
     * @return 最近批次包装
     */
    LatestBatchView latest(Long chapterId, String sourceKind, String sourceObjectId);

    /**
     * 查询标题候选批次。
     *
     * @param chapterId 章节 ID
     * @param batchId 批次 ID
     * @return 批次公开视图
     */
    BatchView get(Long chapterId, Long batchId);

    /**
     * 从失败步骤重试同一个 Agent Run。
     *
     * @param chapterId 章节 ID
     * @param batchId 批次 ID
     * @param request 重试请求
     * @return 批次公开视图
     */
    BatchView retry(Long chapterId, Long batchId, RetryRequest request);

    /**
     * 取消仍在运行的标题候选批次。
     *
     * @param chapterId 章节 ID
     * @param batchId 批次 ID
     * @return 批次公开视图
     */
    BatchView cancel(Long chapterId, Long batchId);

    /**
     * 经作者明确确认后采用候选或其编辑值。
     *
     * @param chapterId 章节 ID
     * @param batchId 批次 ID
     * @param candidateId 候选 ID
     * @param request 采用请求
     * @return 采用结果
     */
    AdoptedTitleView adopt(Long chapterId, Long batchId, Long candidateId, AdoptRequest request);
}
