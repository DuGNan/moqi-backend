package com.dugnan.moqi.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextSnapshotQueryPort;
import com.dugnan.moqi.llm.LlmCallContext;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmExecutionConfigDescriptor;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderError;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseMetadata;
import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 验证场景规划候选工作流的终态、错误分类和模型调用关联。
 */
class ScenePlanWorkflowDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChapterPlanVersionMapper planMapper = mock(ChapterPlanVersionMapper.class);
    private final ScenePlanVersionMapper sceneMapper = mock(ScenePlanVersionMapper.class);
    private final ChapterOutlineQueryMapper outlineMapper = mock(ChapterOutlineQueryMapper.class);
    private final LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
    private final UserConfigService userConfigService = mock(UserConfigService.class);
    private final StoryContextSnapshotQueryPort snapshotQueryPort = mock(StoryContextSnapshotQueryPort.class);
    private final LlmProvider provider = mock(LlmProvider.class);

    private ScenePlanWorkflowDefinition workflow;
    private ChapterPlanVersionEntity candidate;
    private ChapterOutlineEntity outline;

    @BeforeEach
    void setUp() {
        workflow = new ScenePlanWorkflowDefinition(planMapper, sceneMapper, outlineMapper, providerFactory,
                userConfigService, snapshotQueryPort, new PlanningContentCodec(), objectMapper);
        candidate = new ChapterPlanVersionEntity();
        candidate.setId(301L);
        candidate.setWorkId(11L);
        candidate.setChapterId(21L);
        candidate.setOutlineId(101L);
        candidate.setOutlineRevision(2);
        candidate.setAiTaskId(401L);
        candidate.setPlanStatus("queued");
        candidate.setVersion(0);
        outline = new ChapterOutlineEntity();
        outline.setId(101L);
        outline.setRevision(2);
        outline.setOutlineContent("主角必须在雨夜完成交易并保住同伴。");
        when(planMapper.selectById(301L)).thenReturn(candidate);
        when(outlineMapper.findLatest(21L)).thenReturn(outline);
        LlmExecutionConfig executionConfig = new LlmExecutionConfig(
                new LlmProviderRuntimeConfig("deepseek", "https://example.invalid", "test-key", "test-model"),
                new LlmExecutionConfigDescriptor("deepseek", "test-model", 1, 1));
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(executionConfig);
        when(providerFactory.createObserved(eq(executionConfig), any(LlmCallContext.class))).thenReturn(provider);
        when(snapshotQueryPort.load(701L)).thenReturn(new StoryContextSnapshot(701L, "scene", 11L, 21L, null,
                com.dugnan.moqi.context.StoryContextProfile.SCENE_PLANNING, 2, 1L, 16384, 4096, 12288, 32,
                "fingerprint", List.of(), List.of(), null));
    }

    @Test
    void completesGenerationWithoutHumanInterruptionAndLinksModelCall() throws Exception {
        when(provider.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
                null,
                objectMapper.readTree(validScenesJson()),
                new LlmResponseMetadata("deepseek", "test-model", "stop", 10, 8, 18, "provider-1", 901L)));

        AgentStepResult result = workflow.execute("generate_candidate", context());

        assertThat(result.nextStepKey()).isNull();
        assertThat(result.interruption()).isNull();
        assertThat(result.modelCallRef()).isEqualTo("901");
        ArgumentCaptor<LlmCallContext> contextCaptor = ArgumentCaptor.forClass(LlmCallContext.class);
        org.mockito.Mockito.verify(providerFactory).createObserved(any(), contextCaptor.capture());
        assertThat(contextCaptor.getValue().aiTaskId()).isEqualTo(401L);
    }

    @Test
    void mapsInvalidStructuredProviderResponseToJsonError() {
        when(provider.generate(any(LlmRequest.class))).thenThrow(new LlmProviderException(LlmProviderError.INVALID_RESPONSE));

        assertThatThrownBy(() -> workflow.execute("generate_candidate", context()))
                .satisfies(exception -> assertThat(workflow.errorCode((Exception) exception))
                        .isEqualTo("SCENE_PLAN_JSON_INVALID"));
    }

    @Test
    void mapsStaleOutlineToStableScenePlanError() {
        outline.setRevision(3);

        assertThatThrownBy(() -> workflow.execute("generate_candidate", context()))
                .satisfies(exception -> assertThat(workflow.errorCode((Exception) exception))
                        .isEqualTo("SCENE_PLAN_OUTLINE_STALE"));
    }

    @Test
    void rejectsInventedSettingReferenceAndKeepsPromptContractExplicit() throws Exception {
        when(provider.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
                null,
                objectMapper.readTree(validScenesJson().replace("\"participants\":[]",
                        "\"participants\":[{\"settingEntryId\":7,\"name\":\"陌生人\"}]")),
                null));

        assertThatThrownBy(() -> workflow.execute("generate_candidate", context()))
                .satisfies(exception -> assertThat(workflow.errorCode((Exception) exception))
                        .isEqualTo("SCENE_PLAN_VALIDATION_FAILED"));
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).generate(requestCaptor.capture());
        assertThat(requestCaptor.getValue().options().responseFormat().name()).isEqualTo("JSON_OBJECT");
        assertThat(requestCaptor.getValue().messages().get(0).content())
                .contains("仅输出 JSON 对象", "status 必须为 planned", "禁止编造 ID");
    }

    @Test
    void rejectsAdditionalTopLevelFields() throws Exception {
        when(provider.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
                null, objectMapper.readTree("{\"scenes\":[],\"summary\":\"unexpected\"}"), null));

        assertThatThrownBy(() -> workflow.execute("generate_candidate", context()))
                .satisfies(exception -> assertThat(workflow.errorCode((Exception) exception))
                        .isEqualTo("SCENE_PLAN_JSON_INVALID"));
    }

    @Test
    void mapsSceneInsertFailureToPersistenceError() throws Exception {
        when(planMapper.update(eq(null), any())).thenReturn(1);
        when(sceneMapper.insert(any(ScenePlanVersionEntity.class))).thenThrow(new IllegalStateException("database unavailable"));
        AgentStepResult result = new AgentStepResult(
                Map.of("scenesJson", objectMapper.writeValueAsString(
                        objectMapper.readTree(validScenesJson()).get("scenes"))),
                Map.of("candidateId", 301L), null, null, null);

        assertThatThrownBy(() -> workflow.applyResult("generate_candidate", context(), result))
                .satisfies(exception -> assertThat(workflow.errorCode((Exception) exception))
                        .isEqualTo("SCENE_PLAN_PERSISTENCE_FAILED"));
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(501L, 601L, "generate_candidate", 1, "501:generate_candidate",
                Map.of("candidateId", 301L, "contextSnapshotId", 701L), Map.of(), Map.of(),
                mock(com.dugnan.moqi.agent.AgentRunCallRegistry.class));
    }

    private String validScenesJson() {
        return """
                {"scenes":[{"sceneKey":"rainy-deal","sequence":1,"title":"雨夜交易","viewpointCharacter":null,
                "timeAnchor":"雨夜","location":null,"goal":"完成交易","conflict":"身份暴露","emotion":"紧张",
                "pacing":"中速","participants":[],"requiredSettings":[],"foreshadowingActions":[],
                "expectedOutcome":"保住同伴","status":"planned"}]}
                """;
    }
}
