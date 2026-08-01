package com.dugnan.moqi.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * Agent Run 查询接口。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 提供断线重连后按持久化状态恢复 Agent Run 视图的只读接口。
 */
@RestController
@RequestMapping("/api/agent-runs")
public class AgentRuntimeController {

    private static final String LOCAL_USER = "local-user";

    private final AgentRuntime agentRuntime;

    /**
     * 创建 Agent Run 查询控制器。
     *
     * @param agentRuntime Agent Runtime 应用端口
     */
    public AgentRuntimeController(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    /**
     * 查询 Agent Run 当前持久化状态。
     *
     * @param runId Agent Run ID
     * @return Agent Run 当前视图
     */
    @GetMapping("/{runId}")
    public ApiResponse<AgentRunView> run(@PathVariable Long runId) {
        return ApiResponse.success(agentRuntime.load(runId, LOCAL_USER));
    }
}
