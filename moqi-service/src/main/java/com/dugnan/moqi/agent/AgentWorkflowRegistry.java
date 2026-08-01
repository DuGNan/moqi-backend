package com.dugnan.moqi.agent;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * 按工作流类型查找已装配的业务定义。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 注册并按工作流类型提供框架无关业务定义。
 */
@Component
public class AgentWorkflowRegistry {

    private final Map<String, AgentWorkflowDefinition> definitions;

    public AgentWorkflowRegistry(List<AgentWorkflowDefinition> definitions) {
        this.definitions = definitions.stream().collect(Collectors.toUnmodifiableMap(
                AgentWorkflowDefinition::workflowType, Function.identity()));
    }

    public AgentWorkflowDefinition require(String workflowType) {
        AgentWorkflowDefinition definition = definitions.get(workflowType);
        if (definition == null) {
            throw new BusinessException(ErrorCode.AGENT_WORKFLOW_NOT_FOUND, "Agent 工作流不存在");
        }
        return definition;
    }
}
