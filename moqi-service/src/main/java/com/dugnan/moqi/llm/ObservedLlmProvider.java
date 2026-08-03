package com.dugnan.moqi.llm;

import java.time.Duration;
import java.util.function.Consumer;

import com.dugnan.moqi.chapter.entity.LlmModelCallEntity;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 以装饰器方式统一记录同步、流式和连接测试模型调用。
 */
final class ObservedLlmProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final LlmExecutionConfig config;
    private final LlmCallContext context;
    private final LlmCallObservationService observationService;

    ObservedLlmProvider(
            LlmProvider delegate,
            LlmExecutionConfig config,
            LlmCallContext context,
            LlmCallObservationService observationService) {
        this.delegate = delegate;
        this.config = config;
        this.context = context;
        this.observationService = observationService;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        LlmModelCallEntity call = observationService.start(config, context);
        long started = System.nanoTime();
        try {
            LlmResponse response = delegate.generate(request);
            LlmResponseMetadata metadata = withCallId(response == null ? null : response.metadata(), call.getId());
            observationService.succeed(call.getId(), metadata, elapsedMillis(started));
            return response == null
                    ? null
                    : new LlmResponse(response.content(), response.structuredContent(), metadata);
        } catch (RuntimeException exception) {
            observationService.fail(call.getId(), exception, elapsedMillis(started));
            throw exception;
        }
    }

    @Override
    public LlmStreamCall stream(LlmRequest request, Consumer<LlmStreamEvent> consumer) {
        LlmModelCallEntity call = observationService.start(config, context);
        long started = System.nanoTime();
        try {
            return new ObservedLlmStreamCall(
                    delegate.stream(request, consumer),
                    call.getId(),
                    started,
                    observationService);
        } catch (RuntimeException exception) {
            observationService.fail(call.getId(), exception, elapsedMillis(started));
            throw exception;
        }
    }

    @Override
    public LlmProviderCapabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public void testConnection() {
        LlmModelCallEntity call = observationService.start(config, context);
        long started = System.nanoTime();
        try {
            delegate.testConnection();
            observationService.succeed(call.getId(), null, elapsedMillis(started));
        } catch (RuntimeException exception) {
            observationService.fail(call.getId(), exception, elapsedMillis(started));
            throw exception;
        }
    }

    private LlmResponseMetadata withCallId(LlmResponseMetadata metadata, Long callId) {
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

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
