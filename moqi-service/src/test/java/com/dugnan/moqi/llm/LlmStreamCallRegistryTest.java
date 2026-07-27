package com.dugnan.moqi.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.task.event.AiTaskCancellationSignal;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 验证模型调用注册表的取消竞态、幂等和清理语义。
 */
class LlmStreamCallRegistryTest {

    @Test
    void cancelsCallRegisteredAfterCancellationSignal() {
        LlmStreamCallRegistry registry = new LlmStreamCallRegistry();
        TestCall call = new TestCall();

        registry.onCancellation(new AiTaskCancellationSignal(12L));
        registry.register(12L, call);
        registry.unregister(12L, call);

        assertThat(call.isCanceled()).isTrue();
        assertThat(registry.activeCallCount()).isZero();
        assertThat(registry.cancellationCount()).isZero();
    }

    @Test
    void repeatedCancellationIsIdempotentAndRegistryHasNoResidue() {
        LlmStreamCallRegistry registry = new LlmStreamCallRegistry();
        TestCall call = new TestCall();
        registry.register(12L, call);

        registry.cancel(12L);
        registry.cancel(12L);
        registry.unregister(12L, call);

        assertThat(call.cancelAttempts()).isEqualTo(2);
        assertThat(registry.activeCallCount()).isZero();
        assertThat(registry.cancellationCount()).isZero();
    }

    private static final class TestCall implements LlmStreamCall {

        private final AtomicBoolean canceled = new AtomicBoolean();
        private int cancelAttempts;

        @Override
        public boolean cancel() {
            cancelAttempts++;
            return canceled.compareAndSet(false, true);
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(
                    canceled.get() ? LlmStreamStatus.CANCELED : LlmStreamStatus.RUNNING,
                    null,
                    null);
        }

        @Override
        public boolean isDone() {
            return canceled.get();
        }

        private boolean isCanceled() {
            return canceled.get();
        }

        private int cancelAttempts() {
            return cancelAttempts;
        }
    }
}
