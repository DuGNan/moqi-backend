package com.dugnan.moqi.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.entity.LlmModelCallEntity;
import com.dugnan.moqi.chapter.mapper.LlmModelCallMapper;
import com.dugnan.moqi.llm.entity.LlmModelPriceEntity;
import com.dugnan.moqi.llm.mapper.LlmModelPriceMapper;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 在独立短事务中维护模型调用尝试、终态、用量和估算成本。
 */
@Service
public class LlmCallObservationService {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);
    private static final int MAX_ERROR_LENGTH = 500;
    private static final String STATUS_RUNNING = "running";
    private static final String ERROR_TIMEOUT = "TIMEOUT";
    private static final String ERROR_RATE_LIMITED = "RATE_LIMITED";
    private static final String ERROR_CANCELED = "CANCELED";

    private final LlmModelCallMapper callMapper;
    private final LlmModelPriceMapper priceMapper;

    public LlmCallObservationService(LlmModelCallMapper callMapper, LlmModelPriceMapper priceMapper) {
        this.callMapper = callMapper;
        this.priceMapper = priceMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RuntimeException.class)
    public LlmModelCallEntity start(LlmExecutionConfig config, LlmCallContext context) {
        LlmExecutionConfigDescriptor descriptor = config.descriptor();
        LlmModelCallEntity call = new LlmModelCallEntity();
        call.setGenerationSceneId(context.generationSceneId());
        call.setAiTaskId(context.aiTaskId());
        call.setConversationId(context.conversationId());
        call.setUserId(context.userId());
        call.setWorkId(context.workId());
        call.setChapterId(context.chapterId());
        call.setWorkflowType(context.workflowType());
        call.setOperationType(context.operationType());
        call.setLogicalCallId(context.logicalCallId());
        call.setAttemptNo(callMapper.selectMaxAttempt(context.logicalCallId()) + 1);
        call.setReplyMode(context.replyMode());
        call.setReplyDepth(context.replyDepth());
        call.setReplyScopeSummary(context.replyScopeSummary());
        call.setControlSource(context.controlSource());
        call.setPolicyVersion(context.policyVersion());
        call.setAgentRunId(context.agentRunId());
        call.setAgentStepId(context.agentStepId());
        call.setProvider(truncate(descriptor.provider(), 64));
        call.setModel(truncate(descriptor.model(), 128));
        call.setConfigVersion(descriptor.configVersion());
        call.setCredentialVersion(descriptor.credentialVersion());
        call.setPromptTemplateVersion(context.promptTemplateVersion());
        call.setRequestHash(hashReference(context));
        call.setCallStatus(STATUS_RUNNING);
        call.setCostStatus("unpriced");
        call.setStartedAt(LocalDateTime.now());
        call.setDeleted(0);
        call.setVersion(0);
        callMapper.insert(call);
        return call;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RuntimeException.class)
    public void succeed(Long callId, LlmResponseMetadata metadata, long elapsedMillis) {
        LlmModelCallEntity call = requireRunning(callId);
        CostEstimate cost = estimate(call, metadata);
        callMapper.update(null, baseUpdate(call)
                .set("call_status", "succeeded")
                .set("finish_reason", metadata == null ? null : truncate(metadata.finishReason(), 64))
                .set("provider_request_id", metadata == null ? null : truncate(metadata.providerRequestId(), 255))
                .set("input_tokens", metadata == null ? null : metadata.inputTokens())
                .set("output_tokens", metadata == null ? null : metadata.outputTokens())
                .set("total_tokens", metadata == null ? null : metadata.totalTokens())
                .set("price_version_id", cost.priceVersionId())
                .set("estimated_cost", cost.estimatedCost())
                .set("currency", cost.currency())
                .set("cost_status", cost.status())
                .set("finished_at", LocalDateTime.now())
                .set("elapsed_millis", elapsedMillis)
                .set("version", call.getVersion() + 1));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RuntimeException.class)
    public void fail(Long callId, Throwable exception, long elapsedMillis) {
        LlmModelCallEntity call = requireRunning(callId);
        String errorCode = errorCode(exception);
        callMapper.update(null, baseUpdate(call)
                .set("call_status", status(errorCode))
                .set("error_category", errorCategory(errorCode))
                .set("error_code", errorCode)
                .set("error_message", safeMessage(errorCode))
                .set("finished_at", LocalDateTime.now())
                .set("elapsed_millis", elapsedMillis)
                .set("version", call.getVersion() + 1));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RuntimeException.class)
    public void finishStream(Long callId, LlmStreamResult result, long elapsedMillis) {
        if (result != null && result.status() == LlmStreamStatus.COMPLETED) {
            succeed(callId, result.metadata(), elapsedMillis);
            return;
        }
        LlmProviderError error = result == null ? LlmProviderError.INVALID_RESPONSE : result.error();
        if (result != null && result.status() == LlmStreamStatus.CANCELED) {
            error = null;
        }
        fail(callId, error == null
                ? new LlmCallCanceledException()
                : new LlmProviderException(error), elapsedMillis);
    }

    private LlmModelCallEntity requireRunning(Long callId) {
        LlmModelCallEntity call = callId == null ? null : callMapper.selectById(callId);
        if (call == null || !STATUS_RUNNING.equals(call.getCallStatus())) {
            return terminalPlaceholder(callId);
        }
        return call;
    }

    private LlmModelCallEntity terminalPlaceholder(Long callId) {
        LlmModelCallEntity placeholder = new LlmModelCallEntity();
        placeholder.setId(callId == null ? -1L : callId);
        placeholder.setVersion(-1);
        return placeholder;
    }

    private UpdateWrapper<LlmModelCallEntity> baseUpdate(LlmModelCallEntity call) {
        return new UpdateWrapper<LlmModelCallEntity>()
                .eq("id", call.getId())
                .eq("deleted", 0)
                .eq("call_status", STATUS_RUNNING)
                .eq("version", call.getVersion());
    }

    private CostEstimate estimate(LlmModelCallEntity call, LlmResponseMetadata metadata) {
        if (metadata == null || metadata.inputTokens() == null || metadata.outputTokens() == null) {
            return CostEstimate.unpriced();
        }
        LocalDateTime startedAt = call.getStartedAt() == null ? LocalDateTime.now() : call.getStartedAt();
        LlmModelPriceEntity price = priceMapper.selectOne(new LambdaQueryWrapper<LlmModelPriceEntity>()
                .eq(LlmModelPriceEntity::getProvider, call.getProvider())
                .eq(LlmModelPriceEntity::getModel, call.getModel())
                .le(LlmModelPriceEntity::getEffectiveFrom, startedAt)
                .and(wrapper -> wrapper.isNull(LlmModelPriceEntity::getEffectiveTo)
                        .or()
                        .gt(LlmModelPriceEntity::getEffectiveTo, startedAt))
                .eq(LlmModelPriceEntity::getDeleted, 0)
                .orderByDesc(LlmModelPriceEntity::getEffectiveFrom)
                .last("LIMIT 1"));
        if (price == null) {
            return CostEstimate.unpriced();
        }
        BigDecimal inputCost = price.getInputCacheMissPricePerMillion()
                .multiply(BigDecimal.valueOf(metadata.inputTokens()))
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        BigDecimal outputCost = price.getOutputPricePerMillion()
                .multiply(BigDecimal.valueOf(metadata.outputTokens()))
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        return new CostEstimate(
                price.getId(),
                inputCost.add(outputCost).setScale(8, RoundingMode.HALF_UP),
                price.getCurrency(),
                "estimated");
    }

    private String hashReference(LlmCallContext context) {
        String reference = context.logicalCallId() + ":" + context.sourceFingerprint()
                + ":" + context.promptTemplateVersion();
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(reference.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行时不支持 SHA-256", exception);
        }
    }

    private String errorCode(Throwable exception) {
        if (exception instanceof LlmProviderException providerException) {
            return providerException.getError().name();
        }
        if (exception instanceof LlmCallCanceledException) {
            return ERROR_CANCELED;
        }
        return "INTERNAL_ERROR";
    }

    private String status(String errorCode) {
        return ERROR_CANCELED.equals(errorCode) ? "canceled" : "failed";
    }

    private String errorCategory(String errorCode) {
        if (ERROR_TIMEOUT.equals(errorCode) || ERROR_RATE_LIMITED.equals(errorCode)) {
            return errorCode.toLowerCase(Locale.ROOT);
        }
        return ERROR_CANCELED.equals(errorCode) ? "canceled" : "provider";
    }

    private String safeMessage(String errorCode) {
        String message = "模型调用未成功，安全错误码：" + errorCode;
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private String truncate(String value, int limit) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private record CostEstimate(
            Long priceVersionId,
            BigDecimal estimatedCost,
            String currency,
            String status) {

        private static CostEstimate unpriced() {
            return new CostEstimate(null, null, null, "unpriced");
        }
    }

    private static final class LlmCallCanceledException extends RuntimeException {
    }
}
