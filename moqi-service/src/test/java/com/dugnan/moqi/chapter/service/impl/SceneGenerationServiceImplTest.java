package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.mapper.AgentRunStepMapper;
import com.dugnan.moqi.agent.entity.AgentRunStepEntity;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.CreateSceneGenerationRequest;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationSceneMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmExecutionConfigDescriptor;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanContent;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;
import com.dugnan.moqi.planning.PublishedScenePlanQueryPort;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 验证场景正文生成批次的选择、幂等关联与运行任务创建规则。
 */
@ExtendWith(MockitoExtension.class)
class SceneGenerationServiceImplTest {

    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private ChapterGenerationMapper generationMapper;
    @Mock
    private ChapterGenerationSceneMapper sceneMapper;
    @Mock
    private AiTaskMapper taskMapper;
    @Mock
    private AgentRunStepMapper agentRunStepMapper;
    @Mock
    private PublishedScenePlanQueryPort scenePlanQueryPort;
    @Mock
    private UserConfigService userConfigService;
    @Mock
    private AgentRuntime agentRuntime;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ChapterGenerationBriefService briefService;

    private SceneGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SceneGenerationServiceImpl(
                chapterMapper,
                generationMapper,
                sceneMapper,
                taskMapper,
                agentRunStepMapper,
                scenePlanQueryPort,
                userConfigService,
                agentRuntime,
                new ObjectMapper(),
                eventPublisher,
                new com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy(),
                briefService);
        org.mockito.Mockito.lenient().when(briefService.compile(any(), any())).thenReturn(brief());
    }

    @Test
    void createsAllPlannedScenesAndBindsTheAgentRun() {
        ChapterEntity chapter = chapter();
        ChapterPlanView plan = plan();
        when(chapterMapper.selectById(12L)).thenReturn(chapter);
        when(generationMapper.selectOne(any())).thenReturn(null);
        when(scenePlanQueryPort.loadCurrent(12L)).thenReturn(plan);
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(executionConfig());
        when(taskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiTaskEntity.class).setId(41L);
            return 1;
        });
        when(generationMapper.insert(any(ChapterGenerationEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChapterGenerationEntity.class).setId(51L);
            return 1;
        });
        when(sceneMapper.insert(any(ChapterGenerationSceneEntity.class))).thenReturn(1);
        when(agentRuntime.start(any())).thenReturn(run());
        when(generationMapper.update(any(), any())).thenReturn(1);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(generationMapper.selectById(51L)).thenAnswer(invocation -> {
            ChapterGenerationEntity generation = new ChapterGenerationEntity();
            generation.setId(51L);
            generation.setAiTaskId(41L);
            generation.setAgentRunId(61L);
            generation.setChapterPlanVersionId(31L);
            generation.setGenerationStatus("queued");
            return generation;
        });

        var result = service.create(12L, new CreateSceneGenerationRequest(
                null, "all", null, List.of(), null, "scene-all-1", "about_3000", null, 0.7D));

        ArgumentCaptor<ChapterGenerationSceneEntity> scenes = ArgumentCaptor.forClass(ChapterGenerationSceneEntity.class);
        ArgumentCaptor<ChapterGenerationEntity> generation = ArgumentCaptor.forClass(ChapterGenerationEntity.class);
        ArgumentCaptor<StartAgentRunCommand> run = ArgumentCaptor.forClass(StartAgentRunCommand.class);
        verify(sceneMapper, org.mockito.Mockito.times(2)).insert(scenes.capture());
        verify(generationMapper).insert(generation.capture());
        verify(agentRuntime).start(run.capture());
        assertThat(result.generationId()).isEqualTo(51L);
        assertThat(result.agentRunId()).isEqualTo(61L);
        assertThat(generation.getValue().getLengthPreset()).isEqualTo("about_3000");
        assertThat(generation.getValue().getCustomWordCount()).isNull();
        assertThat(generation.getValue().getBasisSnapshotJson())
                .contains("chapterGenerationBrief", "chapter-generation-brief-v1", "fingerprint")
                .contains("# Chapter Generation Brief");
        assertThat(run.getValue().input())
                .containsEntry("targetChapterWordCount", 3000)
                .containsEntry("plannedSceneCount", 2)
                .doesNotContainKey("maxOutputTokens");
        assertThat(scenes.getAllValues()).extracting(ChapterGenerationSceneEntity::getSceneStatus)
                .containsOnly("pending");
        assertThat(scenes.getAllValues()).extracting(ChapterGenerationSceneEntity::getSceneKey)
                .containsExactly("scene-1", "scene-2");
    }

    @Test
    void rejectsRewriteWithoutBaseGeneration() {
        ChapterEntity chapter = chapter();
        when(chapterMapper.selectById(12L)).thenReturn(chapter);
        when(generationMapper.selectOne(any())).thenReturn(null);
        when(scenePlanQueryPort.loadCurrent(12L)).thenReturn(plan());
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(executionConfig());
        when(taskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiTaskEntity.class).setId(41L);
            return 1;
        });
        when(generationMapper.insert(any(ChapterGenerationEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChapterGenerationEntity.class).setId(51L);
            return 1;
        });

        assertThatThrownBy(() -> service.create(12L, new CreateSceneGenerationRequest(
                null, "rewrite_selected", null, List.of("scene-1"), null, "rewrite-1",
                "about_3000", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.GENERATION_NOT_FOUND);
    }

    @Test
    void rejectsCustomTargetOutsideTheSupportedChapterRange() {
        when(chapterMapper.selectById(12L)).thenReturn(chapter());

        assertThatThrownBy(() -> service.create(12L, new CreateSceneGenerationRequest(
                null, "all", null, List.of(), null, "custom-too-short",
                "custom", 300, 0.7D)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("customWordCount");
    }

    @Test
    void exposesPersistedContentAndSafeFailureRetryMetadata() {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(51L);
        generation.setAgentRunId(61L);
        generation.setDeleted(0);
        ChapterGenerationSceneEntity scene = new ChapterGenerationSceneEntity();
        scene.setId(71L);
        scene.setGenerationId(51L);
        scene.setSceneKey("scene-1");
        scene.setSceneStatus("failed");
        scene.setGeneratedContent("持久化候选正文");
        scene.setDeleted(0);
        AgentRunStepEntity step = new AgentRunStepEntity();
        step.setAttempt(2);
        step.setStepStatus("failed");
        step.setRetryable(1);
        step.setErrorCode("AGENT_STEP_EXECUTION_FAILED");
        step.setErrorMessage("场景模型调用失败");
        when(generationMapper.selectById(51L)).thenReturn(generation);
        when(sceneMapper.selectList(any())).thenReturn(List.of(scene));
        when(agentRunStepMapper.selectOne(any())).thenReturn(step);

        var result = service.listScenes(51L);

        assertThat(result.scenes()).singleElement().satisfies(view -> {
            assertThat(view.generatedContent()).isEqualTo("持久化候选正文");
            assertThat(view.currentAttempt()).isEqualTo(2);
            assertThat(view.retryable()).isTrue();
            assertThat(view.errorCode()).isEqualTo("AGENT_STEP_EXECUTION_FAILED");
            assertThat(view.errorMessage()).isEqualTo("场景模型调用失败");
        });
    }

    @Test
    void doesNotExposeRetryForCanceledScene() {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(51L);
        generation.setAgentRunId(61L);
        generation.setDeleted(0);
        ChapterGenerationSceneEntity scene = new ChapterGenerationSceneEntity();
        scene.setId(71L);
        scene.setGenerationId(51L);
        scene.setSceneKey("scene-1");
        scene.setSceneStatus("canceled");
        scene.setDeleted(0);
        AgentRunStepEntity step = new AgentRunStepEntity();
        step.setAttempt(2);
        step.setStepStatus("failed");
        step.setRetryable(1);
        when(generationMapper.selectById(51L)).thenReturn(generation);
        when(sceneMapper.selectList(any())).thenReturn(List.of(scene));
        when(agentRunStepMapper.selectOne(any())).thenReturn(step);

        var result = service.listScenes(51L);

        assertThat(result.scenes()).singleElement().satisfies(view -> assertThat(view.retryable()).isFalse());
    }

    private ChapterEntity chapter() {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(12L);
        chapter.setWorkId(2L);
        chapter.setDeleted(0);
        return chapter;
    }

    private ChapterPlanView plan() {
        return new ChapterPlanView(31L, 12L, 1, "published", 21L, 1,
                11L, 3, null, null, new ChapterPlanContent("目标", "冲突", "结果"),
                List.of(scene(101L, "scene-1", 1), scene(102L, "scene-2", 2)),
                0, LocalDateTime.now(), LocalDateTime.now());
    }

    private ScenePlanView scene(Long id, String key, int sequence) {
        return new ScenePlanView(id, key, sequence, new ScenePlanContent(key, sequence, key,
                null, null, null, "目标", "冲突", "情绪", "节奏", List.of(), List.of(), List.of(), "结果", "planned"));
    }

    private LlmExecutionConfig executionConfig() {
        return new LlmExecutionConfig(new LlmProviderRuntimeConfig("fake", "http://fake", "secret", "fake-model"),
                new LlmExecutionConfigDescriptor("fake", "fake-model", 3, 7));
    }

    private AgentRunView run() {
        return new AgentRunView(61L, "scene_novel_generation", "queued", 2L, 12L,
                41L, "load", 0L, null, null, null, null, null);
    }

    private ChapterGenerationBrief brief() {
        return new ChapterGenerationBrief(
                1, "chapter-generation-brief-v1", 2L, "作品", 12L, 1, "章节", "任务", "目标", "冲突",
                List.of("开场"), List.of("读者信息"), List.of("因果"), List.of("变化"), List.of("人物边界"),
                List.of(), List.of("结尾"), List.of("自由发挥"), List.of("禁止发明"), List.of(),
                "fingerprint", LocalDateTime.of(2026, 8, 14, 10, 0), "# Chapter Generation Brief");
    }
}
