package com.dugnan.moqi.agent;

import java.time.Duration;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;

/**
 * 业务工作流的框架无关描述，不包含 Graph 类型。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 定义业务工作流步骤、重试限制和结果应用边界。
 */
public interface AgentWorkflowDefinition {

    /**
     * 返回全局唯一的工作流类型。
     *
     * @return 工作流类型
     */
    String workflowType();

    /**
     * 返回首次执行的稳定步骤键。
     *
     * @return 步骤键
     */
    String startStepKey();

    /**
     * 返回单次 Run 的截止时长。
     *
     * @return 截止时长
     */
    Duration timeout();

    /**
     * 返回指定步骤允许的最大尝试次数。
     *
     * @param stepKey 步骤键
     * @return 最大尝试次数
     */
    int maxAttempts(String stepKey);

    /**
     * 在事务外执行步骤计算。
     *
     * @param stepKey 步骤键
     * @param context 稳定执行上下文
     * @return 步骤计算结果
     * @throws Exception 工作流执行异常
     */
    AgentStepResult execute(String stepKey, AgentStepExecutionContext context) throws Exception;

    /**
     * 在 Runtime 的短事务中应用已验证的步骤结果。
     *
     * @param stepKey 步骤键
     * @param context 稳定执行上下文
     * @param result 已验证步骤结果
     */
    default void applyResult(String stepKey, AgentStepExecutionContext context, AgentStepResult result) {
        // 默认工作流只有运行时状态，不产生领域写入。
    }

    /**
     * 返回供运行时持久化的安全失败分类。
     *
     * @param exception 步骤异常
     * @return 安全失败分类
     */
    default String errorCategory(Exception exception) {
        return "execution";
    }

    /**
     * 返回供运行时和客户端诊断的稳定失败码。
     *
     * @param exception 步骤异常
     * @return 安全失败码
     */
    default String errorCode(Exception exception) {
        return "AGENT_STEP_EXECUTION_FAILED";
    }

    /**
     * 在运行时短事务中收敛领域候选的失败状态。
     *
     * @param stepKey 步骤键
     * @param context 稳定执行上下文
     * @param exception 步骤异常
     */
    default void applyFailure(String stepKey, AgentStepExecutionContext context, Exception exception) {
        // 默认工作流没有领域候选状态需要同步。
    }
}
