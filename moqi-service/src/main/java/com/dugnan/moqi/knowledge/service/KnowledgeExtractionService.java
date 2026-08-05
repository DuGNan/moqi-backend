package com.dugnan.moqi.knowledge.service;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.BatchView;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.CandidateDecision;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ConfirmCandidateRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.IgnoreCandidateRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.RetryExtractionRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.StartExtractionRequest;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 定义已采纳正文的知识提取、恢复和人工确认边界。
 */
public interface KnowledgeExtractionService {

    /**
     * 为已采纳正文创建或复用自动提取批次。
     *
     * @param generationId 正文生成批次 ID
     * @return 提取批次
     */
    BatchView startAcceptedGeneration(Long generationId);

    /**
     * 为已采纳正文创建或复用提取批次。
     *
     * @param chapterId 章节 ID
     * @param generationId 正文生成批次 ID
     * @param request 创建请求
     * @return 提取批次
     */
    BatchView start(Long chapterId, Long generationId, StartExtractionRequest request);

    /**
     * 查询正文最新提取批次。
     *
     * @param chapterId 章节 ID
     * @param generationId 正文生成批次 ID
     * @return 最新提取批次，不存在时返回空
     */
    BatchView latest(Long chapterId, Long generationId);

    /**
     * 查询指定提取批次。
     *
     * @param chapterId 章节 ID
     * @param generationId 正文生成批次 ID
     * @param batchId 提取批次 ID
     * @return 提取批次
     */
    BatchView get(Long chapterId, Long generationId, Long batchId);

    /**
     * 重试提取批次的 Provider 步骤。
     *
     * @param chapterId 章节 ID
     * @param generationId 正文生成批次 ID
     * @param batchId 提取批次 ID
     * @param request 重试请求
     * @return Agent Run
     */
    AgentRunView retry(Long chapterId, Long generationId, Long batchId, RetryExtractionRequest request);

    /**
     * 取消提取批次。
     *
     * @param chapterId 章节 ID
     * @param generationId 正文生成批次 ID
     * @param batchId 提取批次 ID
     * @return Agent Run
     */
    AgentRunView cancel(Long chapterId, Long generationId, Long batchId);

    /**
     * 显式确认知识候选。
     *
     * @param candidateId 候选 ID
     * @param request 确认请求
     * @return 人工决策结果
     */
    CandidateDecision confirm(Long candidateId, ConfirmCandidateRequest request);

    /**
     * 忽略知识候选。
     *
     * @param candidateId 候选 ID
     * @param request 忽略请求
     * @return 人工决策结果
     */
    CandidateDecision ignore(Long candidateId, IgnoreCandidateRequest request);
}
