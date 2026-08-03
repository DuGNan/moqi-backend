package com.dugnan.moqi.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.chapter.entity.LlmModelCallEntity;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 验证模型 Provider 装饰器统一记录同步、流式、取消和失败尝试。
 */
@ExtendWith(MockitoExtension.class)
class ObservedLlmProviderTest {

    @Mock
    private LlmProvider delegate;

    @Mock
    private LlmCallObservationService observationService;

    @Test
    void recordsSynchronousSuccessAndReturnsCallId() {
        when(observationService.start(any(), any())).thenReturn(call(41L));
        when(delegate.generate(any())).thenReturn(new LlmResponse(
                "result",
                null,
                new LlmResponseMetadata("deepseek", "model", "stop", 10, 4, 14, "request-1")));

        LlmResponse response = provider().generate(request());

        assertThat(response.metadata().modelCallId()).isEqualTo(41L);
        verify(observationService).succeed(org.mockito.ArgumentMatchers.eq(41L), any(), any(Long.class));
    }

    @Test
    void recordsProviderFailureWithoutSwallowingIt() {
        when(observationService.start(any(), any())).thenReturn(call(42L));
        LlmProviderException failure = new LlmProviderException(LlmProviderError.TIMEOUT);
        when(delegate.generate(any())).thenThrow(failure);

        assertThatThrownBy(() -> provider().generate(request())).isSameAs(failure);

        verify(observationService).fail(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.same(failure),
                any(Long.class));
    }

    @Test
    void recordsStreamTerminalOnlyOnceAfterCancellation() {
        when(observationService.start(any(), any())).thenReturn(call(43L));
        when(delegate.stream(any(), any())).thenReturn(new CancelableCall());

        LlmStreamCall call = provider().stream(request(), event -> {
        });
        assertThat(call.cancel()).isTrue();
        assertThat(call.await().status()).isEqualTo(LlmStreamStatus.CANCELED);

        verify(observationService, times(1)).finishStream(
                org.mockito.ArgumentMatchers.eq(43L), any(), any(Long.class));
    }

    @Test
    void recordsUnexpectedStreamAwaitFailure() {
        when(observationService.start(any(), any())).thenReturn(call(44L));
        when(delegate.stream(any(), any())).thenReturn(new ThrowingCall());

        LlmStreamCall call = provider().stream(request(), event -> {
        });

        assertThatThrownBy(call::await).isInstanceOf(IllegalStateException.class);
        verify(observationService).fail(
                org.mockito.ArgumentMatchers.eq(44L), any(IllegalStateException.class), any(Long.class));
    }

    private ObservedLlmProvider provider() {
        LlmExecutionConfig config = new LlmExecutionConfig(
                new LlmProviderRuntimeConfig("deepseek", "https://example.test", "key", "model"),
                new LlmExecutionConfigDescriptor("deepseek", "model", 2, 3));
        return new ObservedLlmProvider(
                delegate,
                config,
                LlmCallContext.builder("workflow", "operation").logicalCallId("logical-1").build(),
                observationService);
    }

    private LlmRequest request() {
        return new LlmRequest(List.of(new LlmMessage(LlmRole.USER, "hello")), null);
    }

    private LlmModelCallEntity call(Long id) {
        LlmModelCallEntity call = new LlmModelCallEntity();
        call.setId(id);
        return call;
    }

    private static final class CancelableCall implements LlmStreamCall {

        private boolean canceled;

        @Override
        public boolean cancel() {
            canceled = true;
            return true;
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(
                    canceled ? LlmStreamStatus.CANCELED : LlmStreamStatus.COMPLETED,
                    null,
                    null);
        }

        @Override
        public boolean isDone() {
            return canceled;
        }
    }

    private static final class ThrowingCall implements LlmStreamCall {

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public LlmStreamResult await() {
            throw new IllegalStateException("provider transport failed");
        }

        @Override
        public boolean isDone() {
            return false;
        }
    }
}
