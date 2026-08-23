package com.dugnan.moqi.chapter.selection;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.AcceptRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ContinueRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.CreateRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.PlanningChangePackageView;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.RetryRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.View;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 定义章节选区讨论、候选恢复和人工采纳的业务边界。
 */
public interface SelectionAssistanceService {

    /**
     * 创建或按幂等键复用选区协助记录。
     *
     * @param chapterId 章节 ID
     * @param request 冻结选区请求
     * @return 持久化记录
     */
    View create(Long chapterId, CreateRequest request);

    /**
     * 读取持久化的选区协助状态。
     *
     * @param requestId 选区协助记录 ID
     * @return 持久化记录
     */
    View get(Long requestId);

    /**
     * 重试失败的模型生成步骤。
     *
     * @param requestId 选区协助记录 ID
     * @param request 重试版本请求
     * @return Agent Run 当前状态
     */
    AgentRunView retry(Long requestId, RetryRequest request);

    /**
     * 取消尚未结束的协助运行。
     *
     * @param requestId 选区协助记录 ID
     * @return Agent Run 当前状态
     */
    AgentRunView cancel(Long requestId);

    /**
     * 显式拒绝已完成的修改候选。
     *
     * @param requestId 选区协助记录 ID
     * @return 拒绝后的记录
     */
    View reject(Long requestId);

    /**
     * 基于已完成候选创建下一代候选。
     *
     * @param requestId 父候选 ID
     * @param request 继续修改请求
     * @return 新一代候选记录
     */
    View continueFrom(Long requestId, ContinueRequest request);

    /**
     * 经正文一致性校验后采纳局部候选。
     *
     * @param requestId 修改候选 ID
     * @param request 正文版本确认请求
     * @return 采纳后的记录
     */
    View accept(Long requestId, AcceptRequest request);

    /**
     * 读取可在服务重启后恢复的规划变更包。
     *
     * @param requestId 修改提案 ID
     * @return 规划变更包
     */
    PlanningChangePackageView getPlanningChangePackage(Long requestId);
}
