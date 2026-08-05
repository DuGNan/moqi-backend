package com.dugnan.moqi.planning;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.BriefView;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.CheckRequest;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.ConsistencyReportView;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.DiscussionProposalRequest;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.RetryRequest;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 定义场景规划一致性报告、门禁和带回讨论的业务边界。
 */
public interface ScenePlanConsistencyService {
    /** 供未启动 Spring 容器的历史单测使用；生产 Bean 必须由依赖注入覆盖。 */
    static ScenePlanConsistencyService noop() {
        return new ScenePlanConsistencyService() {
            @Override public ConsistencyReportView create(Long chapterId, Long planId, CheckRequest request) { throw new UnsupportedOperationException(); }
            @Override public ConsistencyReportView latest(Long chapterId, Long planId) { return null; }
            @Override public ConsistencyReportView get(Long chapterId, Long reportId) { throw new UnsupportedOperationException(); }
            @Override public AgentRunView retry(Long chapterId, Long reportId, RetryRequest request) { throw new UnsupportedOperationException(); }
            @Override public AgentRunView cancel(Long chapterId, Long reportId) { throw new UnsupportedOperationException(); }
            @Override public BriefView createDiscussionProposal(Long chapterId, Long reportId, DiscussionProposalRequest request) { throw new UnsupportedOperationException(); }
            @Override public void requirePublishable(Long chapterId, Long planId, Integer planVersion, Long reportId, Boolean acknowledgeUnknown) { }
            @Override public void requireGenerationAllowed(Long chapterId, Long planId) { }
        };
    }

    /** 创建或复用检查报告。 */
    ConsistencyReportView create(Long chapterId, Long planId, CheckRequest request);

    /** 读取最新报告。 */
    ConsistencyReportView latest(Long chapterId, Long planId);

    /** 读取指定报告。 */
    ConsistencyReportView get(Long chapterId, Long reportId);

    /** 重试失败的评估步骤。 */
    AgentRunView retry(Long chapterId, Long reportId, RetryRequest request);

    /** 取消运行中的报告。 */
    AgentRunView cancel(Long chapterId, Long reportId);

    /** 把选择的问题转换为新的共识草稿。 */
    BriefView createDiscussionProposal(Long chapterId, Long reportId, DiscussionProposalRequest request);

    /** 验证场景规划发布门禁。 */
    void requirePublishable(Long chapterId, Long planId, Integer planVersion, Long reportId, Boolean acknowledgeUnknown);

    /** 验证正文生成门禁。 */
    void requireGenerationAllowed(Long chapterId, Long planId);
}
