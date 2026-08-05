package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.CreateEvaluationRequest;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationReportView;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.RetryEvaluationRequest;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.RevisionCandidateView;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 定义正文候选的一致性评价、恢复、取消和修订候选查询边界。
 */
public interface GenerationEvaluationService {

    /** 创建或按幂等键复用评价报告。 */
    EvaluationReportView create(Long chapterId, Long generationId, CreateEvaluationRequest request);

    /** 查询批次或场景的最新报告。 */
    EvaluationReportView latest(Long chapterId, Long generationId, Long generationSceneId);

    /** 查询指定评价报告。 */
    EvaluationReportView get(Long chapterId, Long generationId, Long reportId);

    /** 重试失败的语义评价步骤。 */
    AgentRunView retry(Long chapterId, Long generationId, Long reportId, RetryEvaluationRequest request);

    /** 取消尚未终结的评价运行。 */
    AgentRunView cancel(Long chapterId, Long generationId, Long reportId);

    /** 查询报告关联的局部修订候选。 */
    RevisionCandidateView revisionCandidate(Long chapterId, Long generationId, Long reportId);
}
