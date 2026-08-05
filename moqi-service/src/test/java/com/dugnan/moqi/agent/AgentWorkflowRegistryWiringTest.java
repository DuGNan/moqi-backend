package com.dugnan.moqi.agent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import com.dugnan.moqi.agent.infrastructure.GraphAgentWorkflowInvoker;
import com.dugnan.moqi.agent.mapper.AgentCheckpointMapper;
import com.dugnan.moqi.agent.mapper.AgentInterruptionMapper;
import com.dugnan.moqi.agent.mapper.AgentRunMapper;
import com.dugnan.moqi.agent.mapper.AgentRunStepMapper;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationRevisionCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusCodec;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusValidator;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContentCodec;
import com.dugnan.moqi.chapter.service.impl.GenerationEvaluationServiceImpl;
import com.dugnan.moqi.chapter.workflow.GenerationEvaluationWorkflowDefinition;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.mapper.StoryContextSnapshotMapper;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.planning.ScenePlanConsistencyServiceImpl;
import com.dugnan.moqi.planning.ScenePlanConsistencyWorkflowDefinition;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanConsistencyReportMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 验证多个双向依赖业务工作流与 Agent Runtime 可以在禁止循环依赖时完成容器装配。
 */
class AgentWorkflowRegistryWiringTest {

    @Test
    void startsContextWithoutCircularDependency() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            registerMock(context, AgentRunMapper.class);
            registerMock(context, AgentRunStepMapper.class);
            registerMock(context, AgentCheckpointMapper.class);
            registerMock(context, AgentInterruptionMapper.class);
            registerMock(context, WorkMapper.class);
            registerMock(context, ChapterMapper.class);
            registerMock(context, AiTaskMapper.class);
            registerMock(context, GraphAgentWorkflowInvoker.class);
            registerMock(context, AgentRunCallRegistry.class);
            registerMock(context, TransactionTemplate.class);
            registerMock(context, ChapterGenerationMapper.class);
            registerMock(context, ChapterGenerationSceneMapper.class);
            registerMock(context, ChapterGenerationEvaluationReportMapper.class);
            registerMock(context, ChapterGenerationRevisionCandidateMapper.class);
            registerMock(context, StoryContextSnapshotMapper.class);
            registerMock(context, ScenePlanVersionMapper.class);
            registerMock(context, ChapterPlanVersionMapper.class);
            registerMock(context, ScenePlanConsistencyReportMapper.class);
            registerMock(context, ChapterOutlineQueryMapper.class);
            registerMock(context, ChapterBriefMapper.class);
            registerMock(context, ChapterConsensusCodec.class);
            registerMock(context, ChapterConsensusValidator.class);
            registerMock(context, OutlineCandidateContentCodec.class);
            registerMock(context, LlmProviderFactory.class);
            registerMock(context, UserConfigService.class);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(
                    AgentRuntimeService.class,
                    AgentWorkflowRegistry.class,
                    GenerationEvaluationWorkflowDefinition.class,
                    GenerationEvaluationServiceImpl.class,
                    ScenePlanConsistencyWorkflowDefinition.class,
                    ScenePlanConsistencyServiceImpl.class);

            assertThatCode(context::refresh).doesNotThrowAnyException();
        }
    }

    private <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }
}
