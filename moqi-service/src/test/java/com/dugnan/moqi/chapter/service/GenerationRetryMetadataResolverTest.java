package com.dugnan.moqi.chapter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.agent.entity.AgentRunEntity;
import com.dugnan.moqi.agent.entity.AgentRunStepEntity;
import com.dugnan.moqi.agent.mapper.AgentRunMapper;
import com.dugnan.moqi.agent.mapper.AgentRunStepMapper;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证 Agent Run 恢复元数据从最新失败步骤稳定解析。
 */
@ExtendWith(MockitoExtension.class)
class GenerationRetryMetadataResolverTest {

    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentRunStepMapper stepMapper;
    @InjectMocks
    private GenerationRetryMetadataResolver resolver;

    @Test
    void resolvesCurrentStepAndRetryableAttempt() {
        AgentRunEntity run = new AgentRunEntity();
        run.setCurrentStepKey("generate_chapter");
        AgentRunStepEntity step = new AgentRunStepEntity();
        step.setAttempt(2);
        step.setStepStatus("failed");
        step.setRetryable(1);
        when(runMapper.selectById(9L)).thenReturn(run);
        when(stepMapper.selectList(any())).thenReturn(List.of(step));

        var result = resolver.resolve(9L, "generate_chapter");

        assertThat(result.currentStepKey()).isEqualTo("generate_chapter");
        assertThat(result.currentAttempt()).isEqualTo(2);
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void returnsSafeEmptyMetadataWithoutRunId() {
        var result = resolver.resolve(null, "generate_chapter");

        assertThat(result.currentStepKey()).isNull();
        assertThat(result.currentAttempt()).isNull();
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void resolvesOwnedFailedSemanticStepOnlyWhenEveryBindingMatches() {
        AgentRunEntity run = ownedRun();
        AgentRunStepEntity step = failedStep();
        when(runMapper.selectById(9L)).thenReturn(run);
        when(stepMapper.selectList(any())).thenReturn(List.of(step));

        var result = resolver.resolveOwned(9L, "semantic_evaluate", "chapter_generation_evaluation_v1",
                1L, 12L, 8L);

        assertThat(result.currentAttempt()).isEqualTo(3);
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void returnsSafeEmptyMetadataForMismatchedOwnershipOrMissingStep() {
        AgentRunEntity run = ownedRun();
        when(runMapper.selectById(9L)).thenReturn(run);

        var mismatched = resolver.resolveOwned(9L, "semantic_evaluate", "chapter_generation_evaluation_v1",
                2L, 12L, 8L);
        assertThat(mismatched.currentAttempt()).isNull();
        assertThat(mismatched.retryable()).isFalse();

        when(stepMapper.selectList(any())).thenReturn(List.of());
        var missingStep = resolver.resolveOwned(9L, "semantic_evaluate", "chapter_generation_evaluation_v1",
                1L, 12L, 8L);
        assertThat(missingStep.currentAttempt()).isNull();
        assertThat(missingStep.retryable()).isFalse();
    }

    @Test
    void exposesAttemptButDisablesRetryForNonFailedRun() {
        AgentRunEntity run = ownedRun();
        run.setRunStatus("running");
        when(runMapper.selectById(9L)).thenReturn(run);
        when(stepMapper.selectList(any())).thenReturn(List.of(failedStep()));

        var result = resolver.resolveOwned(9L, "semantic_evaluate", "chapter_generation_evaluation_v1",
                1L, 12L, 8L);

        assertThat(result.currentAttempt()).isEqualTo(3);
        assertThat(result.retryable()).isFalse();
    }

    private AgentRunEntity ownedRun() {
        AgentRunEntity run = new AgentRunEntity();
        run.setDeleted(0);
        run.setWorkflowType("chapter_generation_evaluation_v1");
        run.setWorkId(1L);
        run.setChapterId(12L);
        run.setAiTaskId(8L);
        run.setRunStatus("failed");
        run.setCurrentStepKey("semantic_evaluate");
        return run;
    }

    private AgentRunStepEntity failedStep() {
        AgentRunStepEntity step = new AgentRunStepEntity();
        step.setAttempt(3);
        step.setStepStatus("failed");
        step.setRetryable(1);
        return step;
    }
}
