package com.dugnan.moqi.agent;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentResumeToken;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.ResumeAgentRunCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;

/**
 * 框架无关的可恢复 Agent Runtime 入口。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 定义可恢复 Agent Runtime 的框架无关业务入口。
 */
public interface AgentRuntime {

    /**
     * 创建或按幂等键返回既有 Run。
     *
     * @param command 启动参数
     * @return Run 当前视图
     */
    AgentRunView start(StartAgentRunCommand command);

    /**
     * 读取 Run 及其最新步骤、checkpoint 与人工中断引用。
     *
     * @param runId Run ID
     * @param userId 当前用户 ID
     * @return Run 当前视图
     */
    AgentRunView load(Long runId, String userId);

    /**
     * 消费人工恢复 token 并重新排队 Run。
     *
     * @param command 恢复参数
     * @return Run 当前视图
     */
    AgentRunView resume(ResumeAgentRunCommand command);

    /**
     * 为仍在等待的人工中断重签一次性恢复 token。
     *
     * @param runId Run ID
     * @return 仅向可信调用方返回一次的新 token
     */
    AgentResumeToken reissueResumeToken(Long runId);

    /**
     * 为失败步骤创建下一次执行尝试。
     *
     * @param command 重试参数
     * @return Run 当前视图
     */
    AgentRunView retryStep(RetryAgentStepCommand command);

    /**
     * 取消尚未结束的 Run。
     *
     * @param runId Run ID
     * @return Run 当前视图
     */
    AgentRunView cancel(Long runId);
}
