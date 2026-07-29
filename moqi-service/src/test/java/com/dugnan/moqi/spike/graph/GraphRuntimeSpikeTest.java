package com.dugnan.moqi.spike.graph;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderCapabilities;
import com.dugnan.moqi.llm.LlmProviderError;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseMetadata;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;
import org.junit.jupiter.api.Test;

/**
 * Graph Runtime 隔离验证测试。
 *
 * @author DuGN
 * @date 2026-07-29
 * @description 验证流式输出、中断恢复、取消、超时和稳定标识映射。
 */
class GraphRuntimeSpikeTest {

    private static final GraphRuntimeSpike.SpikeRun RUN =
            new GraphRuntimeSpike.SpikeRun(42L, 4201L, "写一段雨夜重逢的场景");

    @Test
    void streamsInterruptsResumesAndRejectsDuplicateResume() throws Exception {
        ScriptedFakeProvider provider = ScriptedFakeProvider.completed("雨夜", "重逢");
        GraphRuntimeSpike spike = new GraphRuntimeSpike(provider, MemorySaver.builder().build());
        List<GraphRuntimeSpike.SpikeEvent> events = new ArrayList<>();

        GraphRuntimeSpike.SpikeExecution interrupted = spike.start(RUN, events::add);

        assertThat(interrupted.status()).isEqualTo(GraphRuntimeSpike.SpikeStatus.INTERRUPTED);
        assertThat(interrupted.nodes()).containsExactly(
                START,
                GraphRuntimeSpike.LOAD_CONTEXT,
                GraphRuntimeSpike.DRAFT,
                GraphRuntimeSpike.REVIEW,
                GraphRuntimeSpike.REVIEW);
        assertThat(interrupted.state())
                .containsEntry("draftText", "雨夜重逢")
                .containsEntry("reviewResult", "approved-for-human-confirmation");
        assertThat(events).containsSubsequence(
                new GraphRuntimeSpike.SpikeEvent.TextDelta("雨夜"),
                new GraphRuntimeSpike.SpikeEvent.TextDelta("重逢"));
        assertThat(provider.lastRequest().messages()).hasSize(2);

        GraphRuntimeSpike.SpikeExecution completed = spike.resume(RUN, events::add);

        assertThat(completed.status()).isEqualTo(GraphRuntimeSpike.SpikeStatus.COMPLETED);
        assertThat(completed.nodes()).containsExactly(GraphRuntimeSpike.COMPLETE, END);
        assertThat(completed.state()).containsEntry("status", "completed");
        assertThat(spike.restoredState(RUN)).hasValueSatisfying(state ->
                assertThat(state).containsEntry("status", "completed"));
        assertThatThrownBy(() -> spike.resume(RUN, events::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复 resume");
    }

    @Test
    void propagatesProviderTimeoutAsSafeProviderError() throws Exception {
        GraphRuntimeSpike spike = new GraphRuntimeSpike(
                ScriptedFakeProvider.failed(LlmProviderError.TIMEOUT),
                MemorySaver.builder().build());

        assertThatThrownBy(() -> spike.start(RUN, ignored -> { }))
                .isInstanceOf(LlmProviderException.class)
                .extracting(exception -> ((LlmProviderException) exception).getError())
                .isEqualTo(LlmProviderError.TIMEOUT);
    }

    @Test
    void bridgesRunCancellationToActiveProviderCall() throws Exception {
        BlockingFakeCall call = new BlockingFakeCall();
        GraphRuntimeSpike spike = new GraphRuntimeSpike(
                new ScriptedFakeProvider(call),
                MemorySaver.builder().build());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<GraphRuntimeSpike.SpikeExecution> execution =
                    executor.submit(() -> spike.start(RUN, ignored -> { }));
            assertThat(call.awaitStarted(Duration.ofSeconds(5))).isTrue();

            assertThat(spike.cancel(RUN)).isTrue();

            assertThatThrownBy(() -> execution.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(call.isDone()).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void mapsBusinessIdentifiersToStableFrameworkThreadId() {
        assertThat(RUN.threadId()).isEqualTo("ai-task:42:run:4201");
        assertThat(new GraphRuntimeSpike.SpikeRun(42L, 4201L, "另一个提示").threadId())
                .isEqualTo(RUN.threadId());
    }

    private static final class ScriptedFakeProvider implements LlmProvider {

        private final LlmStreamCall call;
        private LlmRequest lastRequest;

        private ScriptedFakeProvider(LlmStreamCall call) {
            this.call = call;
        }

        static ScriptedFakeProvider completed(String... chunks) {
            return new ScriptedFakeProvider(new CompletedFakeCall(List.of(chunks)));
        }

        static ScriptedFakeProvider failed(LlmProviderError error) {
            return new ScriptedFakeProvider(new FailedFakeCall(error));
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            throw new UnsupportedOperationException("Spike 仅验证流式 Provider");
        }

        @Override
        public LlmStreamCall stream(LlmRequest request, Consumer<LlmStreamEvent> consumer) {
            lastRequest = request;
            if (call instanceof CompletedFakeCall completedCall) {
                completedCall.emitTo(consumer);
            }
            return call;
        }

        @Override
        public LlmProviderCapabilities capabilities() {
            return new LlmProviderCapabilities(true, false, false, 4096, 1024);
        }

        @Override
        public void testConnection() {
        }

        LlmRequest lastRequest() {
            return lastRequest;
        }
    }

    private static final class CompletedFakeCall implements LlmStreamCall {

        private final List<String> chunks;

        private CompletedFakeCall(List<String> chunks) {
            this.chunks = chunks;
        }

        void emitTo(Consumer<LlmStreamEvent> consumer) {
            chunks.forEach(chunk -> consumer.accept(new LlmStreamEvent.TextDelta(chunk)));
            consumer.accept(new LlmStreamEvent.Completed(metadata()));
        }

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(LlmStreamStatus.COMPLETED, metadata(), null);
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }

    private static final class FailedFakeCall implements LlmStreamCall {

        private final LlmProviderError error;

        private FailedFakeCall(LlmProviderError error) {
            this.error = error;
        }

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(LlmStreamStatus.FAILED, null, error);
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }

    private static final class BlockingFakeCall implements LlmStreamCall {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final AtomicBoolean canceled = new AtomicBoolean();

        @Override
        public boolean cancel() {
            if (!canceled.compareAndSet(false, true)) {
                return false;
            }
            terminal.countDown();
            return true;
        }

        @Override
        public LlmStreamResult await() {
            started.countDown();
            try {
                if (!terminal.await(10, TimeUnit.SECONDS)) {
                    return new LlmStreamResult(LlmStreamStatus.FAILED, null, LlmProviderError.TIMEOUT);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待取消信号时被中断", exception);
            }
            return new LlmStreamResult(LlmStreamStatus.CANCELED, null, null);
        }

        @Override
        public boolean isDone() {
            return canceled.get();
        }

        boolean awaitStarted(Duration timeout) throws InterruptedException {
            return started.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static LlmResponseMetadata metadata() {
        return new LlmResponseMetadata("fake", "fake-model", "stop", 3, 2, 5, "fake-request");
    }
}
