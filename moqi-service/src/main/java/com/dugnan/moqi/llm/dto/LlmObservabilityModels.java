package com.dugnan.moqi.llm.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 集中定义模型调用观测查询、分页明细和聚合响应。
 */
public final class LlmObservabilityModels {

    private LlmObservabilityModels() {
    }

    public record LlmCallQuery(
            LocalDateTime from,
            LocalDateTime to,
            Long workId,
            Long chapterId,
            String provider,
            String model,
            String workflowType,
            String callStatus,
            Integer page,
            Integer pageSize) {
    }

    public record LlmCallPage(
            long total,
            int page,
            int pageSize,
            List<LlmCallDetail> items) {
    }

    public record LlmCallDetail(
            Long id,
            Long workId,
            Long chapterId,
            Long aiTaskId,
            Long agentRunId,
            Long agentStepId,
            String workflowType,
            String operationType,
            String logicalCallId,
            Integer attemptNo,
            String provider,
            String model,
            String callStatus,
            String finishReason,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            String errorCode,
            Long elapsedMillis,
            BigDecimal estimatedCost,
            String currency,
            String costStatus,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {
    }

    public record LlmSummaryQuery(
            LocalDateTime from,
            LocalDateTime to,
            Long workId,
            String provider,
            String model,
            String workflowType,
            String groupBy) {
    }

    public record LlmCallSummary(
            LocalDateTime from,
            LocalDateTime to,
            String groupBy,
            boolean estimatedCost,
            List<LlmCallSummaryItem> items) {
    }

    public record LlmCallSummaryItem(
            String groupKey,
            Long attemptCount,
            Long logicalCallCount,
            Long successCount,
            Long failureCount,
            Long canceledCount,
            Long timeoutCount,
            Long rateLimitedCount,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            BigDecimal estimatedCost,
            Long unpricedCount,
            BigDecimal averageElapsedMillis) {
    }
}
