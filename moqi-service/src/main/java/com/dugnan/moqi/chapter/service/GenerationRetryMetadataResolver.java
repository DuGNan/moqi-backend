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

    /**
     * 仅在 Run 与业务记录的持久化归属完全一致时返回步骤元数据。
     * 归属缺失或冲突时不猜测历史记录，统一返回不可重试。
     */
    public RetryMetadata resolveOwned(Long runId, String stepKey, String workflowType,
            Long workId, Long chapterId, Long aiTaskId) {
        if (runId == null) {
            return RetryMetadata.empty();
        }
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null
                || !Integer.valueOf(0).equals(run.getDeleted())
                || !workflowType.equals(run.getWorkflowType())
                || !java.util.Objects.equals(workId, run.getWorkId())
                || !java.util.Objects.equals(chapterId, run.getChapterId())
                || !java.util.Objects.equals(aiTaskId, run.getAiTaskId())) {
            return RetryMetadata.empty();
        }
        AgentRunStepEntity step = latestStep(runId, stepKey);
        if (step == null) {
            return RetryMetadata.empty();
        }
        boolean retryable = STATUS_FAILED.equals(run.getRunStatus())
                && STATUS_FAILED.equals(step.getStepStatus())
                && Integer.valueOf(1).equals(step.getRetryable());
        return new RetryMetadata(run.getCurrentStepKey(), step.getAttempt(), retryable);
    }

    private AgentRunStepEntity latestStep(Long runId, String stepKey) {
        return stepMapper.selectList(new LambdaQueryWrapper<AgentRunStepEntity>()
                .eq(AgentRunStepEntity::getRunId, runId)
                .eq(AgentRunStepEntity::getStepKey, stepKey)
                .eq(AgentRunStepEntity::getDeleted, 0)
                .orderByDesc(AgentRunStepEntity::getAttempt)
                .orderByDesc(AgentRunStepEntity::getId)
                .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /** 前端可见的最小重试恢复元数据。 */
    public record RetryMetadata(String currentStepKey, Integer currentAttempt, Boolean retryable) {

        public static RetryMetadata empty() {
            return new RetryMetadata(null, null, false);
        }
    }
}
