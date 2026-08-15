package com.dugnan.moqi.chapter.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.dugnan.moqi.agent.AgentRunCallRegistry;
import com.dugnan.moqi.agent.AgentRuntimeService;
import com.dugnan.moqi.agent.AgentWorkflowRegistry;
import com.dugnan.moqi.agent.infrastructure.GraphAgentWorkflowInvoker;
import com.dugnan.moqi.agent.mapper.AgentCheckpointMapper;
import com.dugnan.moqi.agent.mapper.AgentInterruptionMapper;
import com.dugnan.moqi.agent.mapper.AgentRunMapper;
import com.dugnan.moqi.agent.mapper.AgentRunStepMapper;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterCapacityAssessmentMapper;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextEngine;
import com.dugnan.moqi.context.StoryContextSnapshotQueryPort;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.planning.PlanningContentCodec;
import com.dugnan.moqi.planning.ScenePlanConsistencyService;
import com.dugnan.moqi.planning.StoryPlanningServiceImpl;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.planning.mapper.WorkNarrativePlanVersionMapper;
import com.dugnan.moqi.sourcechain.SourcePropagationService;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证容量评估工作流与 Agent Runtime 的真实 Bean 图不存在循环依赖。
 */
class ChapterCapacityApplicationContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.main.allow-circular-references=false")
            .withUserConfiguration(CapacityRuntimeConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(AgentRunMapper.class, () -> mock(AgentRunMapper.class))
            .withBean(AgentRunStepMapper.class, () -> mock(AgentRunStepMapper.class))
            .withBean(AgentCheckpointMapper.class, () -> mock(AgentCheckpointMapper.class))
            .withBean(AgentInterruptionMapper.class, () -> mock(AgentInterruptionMapper.class))
            .withBean(WorkMapper.class, () -> mock(WorkMapper.class))
            .withBean(ChapterMapper.class, () -> mock(ChapterMapper.class))
            .withBean(AiTaskMapper.class, () -> mock(AiTaskMapper.class))
            .withBean(GraphAgentWorkflowInvoker.class, () -> mock(GraphAgentWorkflowInvoker.class))
            .withBean(AgentRunCallRegistry.class, () -> mock(AgentRunCallRegistry.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(ChapterCapacityAssessmentMapper.class, () -> mock(ChapterCapacityAssessmentMapper.class))
            .withBean(ChapterGenerationBriefService.class, () -> mock(ChapterGenerationBriefService.class))
            .withBean(LlmProviderFactory.class, () -> mock(LlmProviderFactory.class))
            .withBean(UserConfigService.class, () -> mock(UserConfigService.class))
            .withBean(ChapterOutlineQueryMapper.class, () -> mock(ChapterOutlineQueryMapper.class))
            .withBean(WorkNarrativePlanVersionMapper.class, () -> mock(WorkNarrativePlanVersionMapper.class))
            .withBean(ChapterPlanVersionMapper.class, () -> mock(ChapterPlanVersionMapper.class))
            .withBean(ScenePlanVersionMapper.class, () -> mock(ScenePlanVersionMapper.class))
            .withBean(StoryContextEngine.class, () -> mock(StoryContextEngine.class))
            .withBean(StoryContextSnapshotQueryPort.class, () -> mock(StoryContextSnapshotQueryPort.class))
            .withBean(SourcePropagationService.class, () -> mock(SourcePropagationService.class))
            .withBean(ScenePlanConsistencyService.class, () -> mock(ScenePlanConsistencyService.class));

    @Test
    void startsWithoutCircularReferences() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentRuntimeService.class);
            assertThat(context).hasSingleBean(ChapterCapacityAssessmentWorkflowDefinition.class);
            assertThat(context).hasSingleBean(ChapterCapacityAssessmentServiceImpl.class);
            assertThat(context).hasSingleBean(StoryPlanningServiceImpl.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            AgentRuntimeService.class,
            AgentWorkflowRegistry.class,
            ChapterCapacityAssessmentWorkflowDefinition.class,
            ChapterCapacityAssessmentServiceImpl.class,
            ChapterCapacityCompiler.class,
            ChapterGenerationLengthPolicy.class,
            StoryPlanningServiceImpl.class,
            PlanningContentCodec.class
    })
    static class CapacityRuntimeConfiguration {
    }
}
