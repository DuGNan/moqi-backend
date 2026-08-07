package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import com.dugnan.moqi.chapter.dto.ChapterGenerationExperimentModels.RunExperimentRequest;
import com.dugnan.moqi.chapter.entity.ChapterGenerationExperimentEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationExperimentMapper;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmExecutionConfigDescriptor;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseMetadata;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanContent;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;
import com.dugnan.moqi.planning.PublishedScenePlanQueryPort;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-07
 * @description 验证章节生成实验策略的提示输入、模型调用数量与隔离持久化结果。
 */
@ExtendWith(MockitoExtension.class)
class ChapterGenerationExperimentServiceImplTest {

    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private ChapterGenerationExperimentMapper experimentMapper;
    @Mock
    private PublishedScenePlanQueryPort scenePlanQueryPort;
    @Mock
    private UserConfigService userConfigService;
    @Mock
    private LlmProviderFactory providerFactory;
    @Mock
    private LlmProvider provider;

    private ChapterGenerationExperimentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChapterGenerationExperimentServiceImpl(
                chapterMapper,
                experimentMapper,
                scenePlanQueryPort,
                userConfigService,
                providerFactory,
                new ObjectMapper());
        when(experimentMapper.selectOne(any())).thenReturn(null);
        when(chapterMapper.selectById(65L)).thenReturn(chapter());
        when(scenePlanQueryPort.loadCurrent(65L)).thenReturn(plan());
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(executionConfig());
        when(experimentMapper.insert(any(ChapterGenerationExperimentEntity.class))).thenAnswer(invocation -> {
            ChapterGenerationExperimentEntity experiment =
                    invocation.getArgument(0, ChapterGenerationExperimentEntity.class);
            experiment.setId(101L);
            experiment.setGmtCreate(LocalDateTime.now());
            experiment.setGmtModified(LocalDateTime.now());
            return 1;
        });
        when(experimentMapper.updateById(any(ChapterGenerationExperimentEntity.class))).thenReturn(1);
        when(providerFactory.createObserved(any(), any())).thenReturn(provider);
    }

    @Test
    void generatesWholeChapterWithOneObservedModelCall() {
        when(provider.generate(any())).thenReturn(response("整章一次生成正文", 201L));

        var result = service.run(65L, request("whole_chapter_once"));

        assertThat(result.experimentStatus()).isEqualTo("completed");
        assertThat(result.generatedContent()).isEqualTo("整章一次生成正文");
        assertThat(result.modelCallIds()).containsExactly(201L);
        assertThat(result.rawSceneOutputsJson()).isNull();
        verify(providerFactory).createObserved(any(), any());
        verify(provider).generate(any());
    }

    @Test
    void generatesChapterFromLooseStoryIntentWithoutSendingSceneChecklist() {
        String storyIntent = "机械师顾临在星舰遇袭后接过班长托付的玄武，忍痛完成首次反杀。"
                + "他放弃逃生并救出阿澈，随后在废墟中收到神秘信号。";
        when(provider.generate(any())).thenReturn(response("自由创作的整章正文", 251L));

        var result = service.run(
                65L,
                new RunExperimentRequest(
                        "issue-99-loose-test",
                        "loose_story_intent",
                        1,
                        3000,
                        0.7D,
                        storyIntent));

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).generate(requestCaptor.capture());
        LlmRequest modelRequest = requestCaptor.getValue();
        assertThat(result.experimentStatus()).isEqualTo("completed");
        assertThat(result.templateVersion()).isEqualTo("chapter-loose-intent-v1");
        assertThat(result.sceneRouteJson()).contains(storyIntent).doesNotContain("scene-001");
        assertThat(modelRequest.messages().get(0).content())
                .contains("不是必须逐项执行的场景清单", "自行决定场景数量", "时间、地点、环境变化");
        assertThat(modelRequest.messages().get(1).content())
                .contains(storyIntent)
                .doesNotContain("scene-001", "有序场景规划");
        assertThat(result.modelCallIds()).containsExactly(251L);
        assertThat(result.rawSceneOutputsJson()).isNull();
    }

    @Test
    void generatesEachSceneAndThenCohesiveChapterWithoutCreatingFormalCandidate() {
        when(provider.generate(any()))
                .thenReturn(response("场景一", 301L))
                .thenReturn(response("场景二", 302L))
                .thenReturn(response("整章收束正文".repeat(500), 303L));

        var result = service.run(65L, request("scene_then_cohere"));

        assertThat(result.experimentStatus()).isEqualTo("completed");
        assertThat(result.generatedContent()).isEqualTo("整章收束正文".repeat(500));
        assertThat(result.modelCallIds()).containsExactly(301L, 302L, 303L);
        assertThat(result.rawSceneOutputsJson()).contains("场景一", "场景二");
        verify(providerFactory, times(3)).createObserved(any(), any());
        verify(provider, times(3)).generate(any());
    }

    @Test
    void persistsFailureWithoutReturningPartialContentAsAFormalResult() {
        when(provider.generate(any())).thenThrow(new IllegalStateException("provider unavailable"));

        var result = service.run(65L, request("whole_chapter_once"));

        assertThat(result.experimentStatus()).isEqualTo("failed");
        assertThat(result.generatedContent()).isNull();
        assertThat(result.errorMessage()).isEqualTo("实验模型调用失败");
        verify(experimentMapper).updateById(any(ChapterGenerationExperimentEntity.class));
    }

    @Test
    void correctsCohesiveChapterLengthOnceWhenItFallsOutsideTheTargetRange() {
        String correctedContent = "校正后的整章正文".repeat(375);
        when(provider.generate(any()))
                .thenReturn(response("场景一", 401L))
                .thenReturn(response("场景二", 402L))
                .thenReturn(response("过短", 403L))
                .thenReturn(response(correctedContent, 404L));

        var result = service.run(65L, request("scene_then_cohere"));

        assertThat(result.experimentStatus()).isEqualTo("completed");
        assertThat(result.generatedContent()).isEqualTo(correctedContent);
        assertThat(result.modelCallIds()).containsExactly(401L, 402L, 403L, 404L);
        verify(provider, times(4)).generate(any());
    }

    private RunExperimentRequest request(String strategy) {
        return new RunExperimentRequest("issue-99-test", strategy, 1, 3000, 0.7D, null);
    }

    private ChapterEntity chapter() {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(65L);
        chapter.setWorkId(17L);
        chapter.setDeleted(0);
        return chapter;
    }

    private ChapterPlanView plan() {
        return new ChapterPlanView(
                31L,
                65L,
                2,
                "published",
                21L,
                1,
                11L,
                3,
                null,
                null,
                new ChapterPlanContent("目标", "冲突", "结果"),
                List.of(scene(101L, "scene-001", 1), scene(102L, "scene-002", 2)),
                0,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private ScenePlanView scene(Long id, String key, int sequence) {
        return new ScenePlanView(
                id,
                key,
                sequence,
                new ScenePlanContent(
                        key,
                        sequence,
                        key,
                        null,
                        null,
                        null,
                        "目标",
                        "冲突",
                        "情绪",
                        "节奏",
                        List.of(),
                        List.of(),
                        List.of(),
                        "结果",
                        "planned"));
    }

    private LlmExecutionConfig executionConfig() {
        return new LlmExecutionConfig(
                new LlmProviderRuntimeConfig("fake", "http://fake", "secret", "fake-model"),
                new LlmExecutionConfigDescriptor("fake", "fake-model", 3, 7));
    }

    private LlmResponse response(String content, Long modelCallId) {
        return new LlmResponse(
                content,
                null,
                new LlmResponseMetadata(
                        "fake",
                        "fake-model",
                        "stop",
                        10,
                        20,
                        30,
                        "request-" + modelCallId,
                        modelCallId));
    }
}
