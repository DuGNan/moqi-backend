package com.dugnan.moqi.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamResult;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 验证 Agent Run 取消意图可在模型调用注册前后传递。
 */
class AgentRunCallRegistryTest {

    @Test
    void cancelsCallRegisteredAfterRunWasCanceled() {
        AgentRunCallRegistry registry = new AgentRunCallRegistry();
        TestCall call = new TestCall();

        registry.cancel(73L);
        registry.register(73L, call);

        assertThat(call.canceled()).isTrue();
    }

    @Test
    void doesNotRetainCancellationForRunWithoutActiveExecution() {
        AgentRunCallRegistry registry = new AgentRunCallRegistry();
        TestCall call = new TestCall();

        registry.cancel(73L, false);
        registry.register(73L, call);

        assertThat(call.canceled()).isFalse();
        registry.unregister(73L, call);
    }

    @Test
    void previousStepCannotClearCancellationOwnedByOverlappingNextStep() {
        AgentRunCallRegistry registry = new AgentRunCallRegistry();
        TestCall nextStepCall = new TestCall();

        registry.beginExecution(73L);
        registry.beginExecution(73L);
        registry.cancel(73L);
        registry.endExecution(73L);
        registry.register(73L, nextStepCall);

        assertThat(nextStepCall.canceled()).isTrue();
        registry.unregister(73L, nextStepCall);
        registry.endExecution(73L);
        assertThat(registry.isCancellationRequested(73L)).isFalse();
    }

    private static final class TestCall implements LlmStreamCall {

        private final AtomicBoolean canceled = new AtomicBoolean();

        @Override
        public boolean cancel() {
            return canceled.compareAndSet(false, true);
        }

        @Override
        public LlmStreamResult await() {
            return null;
        }

        @Override
        public boolean isDone() {
            return canceled.get();
        }

        private boolean canceled() {
            return canceled.get();
        }
    }
}
