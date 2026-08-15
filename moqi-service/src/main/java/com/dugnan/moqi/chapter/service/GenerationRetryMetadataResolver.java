package com.dugnan.moqi.chapter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.entity.AgentRunEntity;
import com.dugnan.moqi.agent.entity.AgentRunStepEntity;
import com.dugnan.moqi.agent.mapper.AgentRunMapper;
import com.dugnan.moqi.agent.mapper.AgentRunStepMapper;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 从 Agent Runtime 持久化步骤中解析前端恢复和重试所需的安全元数据。
 */
@Component
public class GenerationRetryMetadataResolver {

    private static final String STATUS_FAILED = "failed";

    private final AgentRunMapper runMapper;
    private final AgentRunStepMapper stepMapper;

    public GenerationRetryMetadataResolver(AgentRunMapper runMapper, AgentRunStepMapper stepMapper) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
    }

    /**
     * 查询指定 Run 和稳定步骤键的最新尝试信息。
     *
     * @param runId Agent Run ID
     * @param stepKey 稳定步骤键
     * @return 当前步骤、尝试次数和是否可重试
     */
    public RetryMetadata resolve(Long runId, String stepKey) {
        if (runId == null) {
            return RetryMetadata.empty();
        }
        AgentRunEntity run = runMapper.selectById(runId);
        AgentRunStepEntity step = stepMapper.selectList(new LambdaQueryWrapper<AgentRunStepEntity>()
                .eq(AgentRunStepEntity::getRunId, runId)
                .eq(AgentRunStepEntity::getStepKey, stepKey)
                .eq(AgentRunStepEntity::getDeleted, 0)
                .orderByDesc(AgentRunStepEntity::getAttempt)
                .orderByDesc(AgentRunStepEntity::getId)
                .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);
        boolean retryable = step != null
                && STATUS_FAILED.equals(step.getStepStatus())
                && Integer.valueOf(1).equals(step.getRetryable());
        return new RetryMetadata(
                run == null ? null : run.getCurrentStepKey(),
                step == null ? null : step.getAttempt(),
                retryable);
    }

    /** 前端可见的最小重试恢复元数据。 */
    public record RetryMetadata(String currentStepKey, Integer currentAttempt, Boolean retryable) {

        private static RetryMetadata empty() {
            return new RetryMetadata(null, null, false);
        }
    }
}
