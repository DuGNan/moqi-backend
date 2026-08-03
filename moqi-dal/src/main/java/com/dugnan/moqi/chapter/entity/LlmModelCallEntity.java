package com.dugnan.moqi.chapter.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 映射不含敏感请求正文的大模型调用审计记录。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("llm_model_calls")
public class LlmModelCallEntity extends BaseEntity {

    private Long generationSceneId;
    private Long aiTaskId;
    private Long conversationId;
    private String userId;
    private Long workId;
    private Long chapterId;
    private String workflowType;
    private String operationType;
    private String logicalCallId;
    private Integer attemptNo;
    private String replyMode;
    private String replyDepth;
    private String replyScopeSummary;
    private String controlSource;
    private String policyVersion;
    private Long agentRunId;
    private Long agentStepId;
    private String provider;
    private String model;
    private Integer configVersion;
    private Integer credentialVersion;
    private String promptTemplateVersion;
    private String requestHash;
    private String callStatus;
    private String finishReason;
    private String providerRequestId;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Long priceVersionId;
    private BigDecimal estimatedCost;
    private String currency;
    private String costStatus;
    private String errorCategory;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long elapsedMillis;
}
