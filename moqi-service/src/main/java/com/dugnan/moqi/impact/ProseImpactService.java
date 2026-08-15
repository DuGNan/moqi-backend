package com.dugnan.moqi.impact;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.impact.ProseImpactModels.CreateReportRequest;
import com.dugnan.moqi.impact.ProseImpactModels.CreateReportResult;
import com.dugnan.moqi.impact.ProseImpactModels.ReportView;
import com.dugnan.moqi.impact.ProseImpactModels.RetryReportRequest;

/**
 * @author dgn
 * @description 管理正文 revision 影响报告的创建、恢复和读取。
 */
public interface ProseImpactService {
    /**
     * 为目标正文 revision 创建幂等影响分析报告并启动可恢复 Agent Run。
     *
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param targetRevisionId 目标正文 revision ID
     * @param request 创建请求
     * @return 报告及 Agent Run
     */
    CreateReportResult create(Long workId, Long chapterId, Long targetRevisionId, CreateReportRequest request);
    /**
     * 读取指定影响报告及其事实变化和受影响资产快照。
     *
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param targetRevisionId 目标正文 revision ID
     * @param reportId 报告 ID
     * @return 报告详情
     */
    ReportView detail(Long workId, Long chapterId, Long targetRevisionId, Long reportId);
    /**
     * 读取目标正文 revision 最新一次影响分析报告。
     *
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param targetRevisionId 目标正文 revision ID
     * @return 最新报告
     */
    ReportView latest(Long workId, Long chapterId, Long targetRevisionId);
    /**
     * 以乐观锁占有失败报告后，从结构化分析步骤恢复 Agent Run。
     *
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @param targetRevisionId 目标正文 revision ID
     * @param reportId 报告 ID
     * @param request 重试请求
     * @return 重试后的 Agent Run
     */
    AgentRunView retry(Long workId, Long chapterId, Long targetRevisionId, Long reportId,
            RetryReportRequest request);
}
