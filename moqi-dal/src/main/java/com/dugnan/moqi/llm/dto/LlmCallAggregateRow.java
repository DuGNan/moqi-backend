package com.dugnan.moqi.llm.dto;

import java.math.BigDecimal;

import lombok.Data;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 承载数据库聚合后的模型调用、错误、用量和估算成本统计。
 */
@Data
public class LlmCallAggregateRow {

    private String groupKey;
    private Long attemptCount;
    private Long logicalCallCount;
    private Long successCount;
    private Long failureCount;
    private Long canceledCount;
    private Long timeoutCount;
    private Long rateLimitedCount;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private BigDecimal estimatedCost;
    private Long unpricedCount;
    private BigDecimal averageElapsedMillis;
}
