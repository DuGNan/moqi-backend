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
}
