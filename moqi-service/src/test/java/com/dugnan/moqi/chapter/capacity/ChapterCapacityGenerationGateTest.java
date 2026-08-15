package com.dugnan.moqi.chapter.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityCompiler.CompiledCapacity;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CapacityResult;
import com.dugnan.moqi.chapter.entity.ChapterCapacityAssessmentEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterCapacityAssessmentMapper;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.service.GenerationRetryMetadataResolver;
import com.dugnan.moqi.chapter.workflow.ChapterGenerationLengthPolicy;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanContent;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PublishedScenePlanQueryPort;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证生成创建只消费当前冻结容量评估并遵守作者显式决策。
 */
@ExtendWith(MockitoExtension.class)
class ChapterCapacityGenerationGateTest {

    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private ChapterCapacityAssessmentMapper assessmentMapper;
    @Mock
    private AiTaskMapper taskMapper;
    @Mock
    private PublishedScenePlanQueryPort planQueryPort;
    @Mock
    private ChapterGenerationBriefService briefService;
    @Mock
    private ChapterCapacityCompiler compiler;
    @Mock
    private AgentRuntime agentRuntime;
    @Mock
    private GenerationRetryMetadataResolver retryMetadataResolver;
    private ObjectMapper objectMapper;
    private ChapterCapacityAssessmentServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ChapterCapacityAssessmentServiceImpl(chapterMapper, assessmentMapper, taskMapper,
                planQueryPort, briefService, new ChapterGenerationLengthPolicy(), compiler, objectMapper, agentRuntime,
                retryMetadataResolver);
    }

    @Test
    void requiresAnAssessmentForDenseBaselineAndAnExplicitDecisionForDenseModelResult() throws Exception {
        CapacityResult dense = result("too_dense");
        when(compiler.compile(any(), any(), anyInt())).thenReturn(
                new CompiledCapacity(Map.of(), dense, "input-hash"));

        assertThatThrownBy(() -> service.resolveForGeneration(plan(), brief(), 1500, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CHAPTER_CAPACITY_ASSESSMENT_REQUIRED);

        when(assessmentMapper.selectById(8L)).thenReturn(entity(dense));
        assertThatThrownBy(() -> service.resolveForGeneration(plan(), brief(), 1500, 8L, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CHAPTER_CAPACITY_DECISION_REQUIRED);

        Map<String, Object> snapshot = service.resolveForGeneration(
                plan(), brief(), 1500, 8L, "continue_long_chapter");
        assertThat(snapshot).containsEntry("assessmentId", 8L)
                .containsEntry("decision", "continue_long_chapter")
                .containsEntry("briefFingerprint", "brief-hash");
    }

    @Test
    void rejectsLongContextAndStaleAssessmentEvidence() throws Exception {
        CapacityResult longContext = result("requires_long_context");
        when(compiler.compile(any(), any(), anyInt())).thenReturn(
                new CompiledCapacity(Map.of(), result("fits"), "input-hash"));
        when(assessmentMapper.selectById(8L)).thenReturn(entity(longContext));

        assertThatThrownBy(() -> service.resolveForGeneration(plan(), brief(), 1500, 8L, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CHAPTER_CAPACITY_LONG_CONTEXT_REQUIRED);

        ChapterCapacityAssessmentEntity stale = entity(result("fits"));
        stale.setBriefFingerprint("old-brief");
        when(assessmentMapper.selectById(9L)).thenReturn(stale);
        assertThatThrownBy(() -> service.resolveForGeneration(plan(), brief(), 1500, 9L, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CHAPTER_CAPACITY_ASSESSMENT_STALE);
    }

    private ChapterCapacityAssessmentEntity entity(CapacityResult result) throws Exception {
        ChapterCapacityAssessmentEntity entity = new ChapterCapacityAssessmentEntity();
        entity.setId(8L);
        entity.setWorkId(2L);
        entity.setChapterId(12L);
        entity.setChapterPlanVersionId(31L);
        entity.setScenePlanNo(4);
        entity.setTargetWordCount(1500);
        entity.setAssessmentStatus("ready");
        entity.setBriefFingerprint("brief-hash");
        entity.setInputFingerprint("input-hash");
        entity.setResultJson(objectMapper.writeValueAsString(result));
        entity.setDeleted(0);
        return entity;
    }

    private CapacityResult result(String status) {
        return new CapacityResult(status, 1200, 1800, List.of("原因"), List.of(), List.of(), List.of(),
                List.of(), List.of(), "model", null, "requires_long_context".equals(status));
    }

    private ChapterPlanView plan() {
        return new ChapterPlanView(31L, 12L, 4, "published", null, null, 21L, 3,
                null, null, new ChapterPlanContent("", "", ""), List.of(), 2, "not_required",
                null, null, List.of(), "current", List.of(), null, null, 5, null, null);
    }

    private ChapterGenerationBrief brief() {
        return new ChapterGenerationBrief(1, "chapter-generation-brief-v1", 2L, "作品", 12L, 1,
                "章节", "任务", "目标", "冲突", List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), "brief-hash",
                LocalDateTime.of(2026, 8, 15, 0, 0), "# Chapter Generation Brief");
    }
}
