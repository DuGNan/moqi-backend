package com.dugnan.moqi.chapter.capacity;

import java.util.Map;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CapacityAssessmentView;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CreateAssessmentRequest;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.RetryAssessmentRequest;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 定义章节容量评估的创建、恢复和生成门禁边界。
 */
public interface ChapterCapacityAssessmentService {

    /**
     * 创建或按幂等键复用容量评估。
     *
     * @param chapterId 章节 ID
     * @param request 创建请求
     * @return 容量评估视图
     */
    CapacityAssessmentView create(Long chapterId, CreateAssessmentRequest request);

    /**
     * 读取持久化容量评估。
     *
     * @param assessmentId 评估 ID
     * @return 容量评估视图
     */
    CapacityAssessmentView get(Long assessmentId);

    /**
     * 重试真实失败的语义评估步骤。
     *
     * @param assessmentId 评估 ID
     * @param request 重试请求
     * @return Agent 运行视图
     */
    AgentRunView retry(Long assessmentId, RetryAssessmentRequest request);

    /**
     * 取消尚未终结的容量评估。
     *
     * @param assessmentId 评估 ID
     * @return Agent 运行视图
     */
    AgentRunView cancel(Long assessmentId);

    /**
     * 校验容量结果并返回供生成批次冻结的安全快照。
     *
     * @param plan 当前已发布规划
     * @param brief 当前生成 Brief
     * @param targetWordCount 章节目标字数
     * @param assessmentId 可选容量评估 ID
     * @param decision 可选用户决定
     * @return 供生成批次冻结的容量快照
     */
    Map<String, Object> resolveForGeneration(
            ChapterPlanView plan,
            ChapterGenerationBrief brief,
            int targetWordCount,
            Long assessmentId,
            String decision);
}
