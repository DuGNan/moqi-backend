package com.dugnan.moqi.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Lazy;

import com.dugnan.moqi.chapter.service.impl.GenerationEvaluationServiceImpl;
import com.dugnan.moqi.knowledge.service.impl.KnowledgeExtractionServiceImpl;
import com.dugnan.moqi.planning.ScenePlanConsistencyServiceImpl;

/**
 * @author dgn
 * @date 2026-08-06
 * @description 验证被工作流定义依赖的业务服务不会在构造期反向创建 Agent Runtime。
 */
class AgentWorkflowDependencyWiringTest {

    @Test
    void workflowServicesDoNotRequireAgentRuntimeDuringConstruction() {
        assertLazyAgentRuntimeDependency(GenerationEvaluationServiceImpl.class);
        assertLazyAgentRuntimeDependency(KnowledgeExtractionServiceImpl.class);
        assertLazyAgentRuntimeDependency(ScenePlanConsistencyServiceImpl.class);
    }

    private void assertLazyAgentRuntimeDependency(Class<?> serviceType) {
        boolean requiresAgentRuntime = Arrays.stream(serviceType.getDeclaredConstructors())
                .map(Constructor::getParameterTypes)
                .flatMap(Arrays::stream)
                .anyMatch(AgentRuntime.class::isAssignableFrom);

        assertThat(requiresAgentRuntime)
                .as("%s must not create AgentRuntime while AgentWorkflowRegistry is collecting definitions",
                        serviceType.getSimpleName())
                .isFalse();

        Method setter = Arrays.stream(serviceType.getDeclaredMethods())
                .filter(method -> "setAgentRuntime".equals(method.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(setter.getParameterAnnotations()[0])
                .as("%s AgentRuntime dependency must be resolved after workflow registration",
                        serviceType.getSimpleName())
                .anyMatch(annotation -> annotation.annotationType().equals(Lazy.class));
    }
}
