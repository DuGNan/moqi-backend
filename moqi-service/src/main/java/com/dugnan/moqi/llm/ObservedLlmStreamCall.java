package com.dugnan.moqi.llm;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 包装流式调用句柄并在真实终态出现时完成一次且仅一次观测记录。
 */
final class ObservedLlmStreamCall implements LlmStreamCall {

    private final LlmStreamCall delegate;
    private final Long callId;
    private final long started;
    private final LlmCallObservationService observationService;
    private final AtomicBoolean recorded = new AtomicBoolean();

    ObservedLlmStreamCall(
            LlmStreamCall delegate,
            Long callId,
            long started,
            LlmCallObservationService observationService) {
        this.delegate = delegate;
        this.callId = callId;
        this.started = started;
        this.observationService = observationService;
    }

    @Override
    public boolean cancel() {
        try {
            boolean canceled = delegate.cancel();
            if (canceled) {
                record(new LlmStreamResult(LlmStreamStatus.CANCELED, null, null));
            }
            return canceled;
        } catch (RuntimeException exception) {
            recordFailure(exception);
            throw exception;
        }
    }

    @Override
    public LlmStreamResult await() {
        try {
            LlmStreamResult result = delegate.await();
            LlmStreamResult observed = result == null
                    ? null
                    : new LlmStreamResult(result.status(), withCallId(result.metadata()), result.error());
            record(observed);
            return observed;
        } catch (RuntimeException exception) {
            recordFailure(exception);
            throw exception;
        }
    }

    @Override
    public boolean isDone() {
        return delegate.isDone();
    }

    private void record(LlmStreamResult result) {
        if (recorded.compareAndSet(false, true)) {
            observationService.finishStream(
                    callId,
                    result,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }

    private void recordFailure(RuntimeException exception) {
        if (recorded.compareAndSet(false, true)) {
            observationService.fail(
                    callId,
                    exception,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }

    private LlmResponseMetadata withCallId(LlmResponseMetadata metadata) {
        if (metadata == null) {
            return new LlmResponseMetadata(null, null, null, null, null, null, null, callId);
        }
        return new LlmResponseMetadata(
                metadata.provider(),
                metadata.model(),
                metadata.finishReason(),
                metadata.inputTokens(),
                metadata.outputTokens(),
                metadata.totalTokens(),
                metadata.providerRequestId(),
                callId);
    }
}
