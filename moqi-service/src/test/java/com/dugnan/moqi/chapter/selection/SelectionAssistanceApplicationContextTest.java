package com.dugnan.moqi.chapter.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.AgentWorkflowRegistry;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.service.ProseObjectConversationService;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证工作流注册期间不会沿选区 Brief 依赖反向创建 Agent Runtime。
 */
class SelectionAssistanceApplicationContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CycleRegressionConfiguration.class);

    @Test
    void startsContextWithoutSelectionBriefAgentRuntimeCycle() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentWorkflowRegistry.class);
            assertThat(context).hasSingleBean(SelectionAssistanceWorkflowDefinition.class);
            assertThat(context).hasSingleBean(SelectionAssistanceServiceImpl.class);
        });
    }

    /**
     * @author dgn
     * @date 2026-08-15
     * @description 复现 Agent Runtime、工作流注册、选区服务和 Brief 之间的启动依赖图。
     */
    @Configuration(proxyBeanMethods = false)
    @Import(SelectionAssistanceServiceImpl.class)
    static class CycleRegressionConfiguration {

        @Bean
        AgentRuntime agentRuntime(AgentWorkflowRegistry workflowRegistry) {
            return mock(AgentRuntime.class);
        }

        @Bean
        AgentWorkflowRegistry agentWorkflowRegistry(List<AgentWorkflowDefinition> definitions) {
            return new AgentWorkflowRegistry(definitions);
        }

        @Bean
        SelectionAssistanceWorkflowDefinition selectionAssistanceWorkflowDefinition(
                SelectionAssistanceServiceImpl service,
                LlmProviderFactory providerFactory,
                UserConfigService userConfigService,
                ObjectMapper objectMapper) {
            return new SelectionAssistanceWorkflowDefinition(service, providerFactory, userConfigService, objectMapper);
        }

        @Bean
        ChapterGenerationBriefService chapterGenerationBriefService(AgentRuntime agentRuntime) {
            return mock(ChapterGenerationBriefService.class);
        }

        @Bean
        ChapterMapper chapterMapper() {
            return mock(ChapterMapper.class);
        }

        @Bean
        ChapterSelectionAssistanceMapper assistanceMapper() {
            return mock(ChapterSelectionAssistanceMapper.class);
        }

        @Bean
        AiTaskMapper taskMapper() {
            return mock(AiTaskMapper.class);
        }

        @Bean
        ChapterProseCandidateMapper candidateMapper() {
            return mock(ChapterProseCandidateMapper.class);
        }

        @Bean
        ChapterGenerationMapper generationMapper() {
            return mock(ChapterGenerationMapper.class);
        }

        @Bean
        ChapterConversationMapper conversationMapper() {
            return mock(ChapterConversationMapper.class);
        }

        @Bean
        ChapterConversationMessageMapper messageMapper() {
            return mock(ChapterConversationMessageMapper.class);
        }

        @Bean
        ProsePlanningChangeService planningChangeService() {
            return mock(ProsePlanningChangeService.class);
        }

        @Bean
        ProseObjectConversationService proseObjectConversationService() {
            return mock(ProseObjectConversationService.class);
        }

        @Bean
        LlmProviderFactory providerFactory() {
            return mock(LlmProviderFactory.class);
        }

        @Bean
        UserConfigService userConfigService() {
            return mock(UserConfigService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
