package com.dugnan.moqi.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 验证自动测试 Fake Provider 遵守 V2 消息、能力与完成事件契约。
 */
class FakeLlmProviderTest {

    @Test
    void drivesOrderedMessagesAndCompletedStream() {
        FakeProvider provider = new FakeProvider();
        LlmRequest request = new LlmRequest(
                List.of(
                        new LlmMessage(LlmRole.SYSTEM, "system"),
                        new LlmMessage(LlmRole.USER, "question"),
                        new LlmMessage(LlmRole.ASSISTANT, "answer"),
                        new LlmMessage(LlmRole.USER, "follow-up")),
                LlmOptions.defaults());
        java.util.ArrayList<LlmStreamEvent> events = new java.util.ArrayList<>();

        LlmStreamResult result = provider.stream(request, events::add).await();

        assertThat(provider.lastRequest().messages()).containsExactlyElementsOf(request.messages());
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new LlmStreamEvent.TextDelta("fake"));
        assertThat(events.get(1)).isInstanceOf(LlmStreamEvent.Completed.class);
        assertThat(result.status()).isEqualTo(LlmStreamStatus.COMPLETED);
    }

    @Test
    void explicitlyRejectsUnsupportedStructuredOutput() {
        FakeProvider provider = new FakeProvider();
        LlmRequest request = new LlmRequest(
                List.of(new LlmMessage(LlmRole.USER, "json")),
                new LlmOptions(null, null, List.of(), LlmResponseFormat.JSON_OBJECT));

        assertThatThrownBy(() -> provider.generate(request))
                .isInstanceOf(LlmProviderException.class)
                .extracting(exception -> ((LlmProviderException) exception).getError())
                .isEqualTo(LlmProviderError.UNSUPPORTED_CAPABILITY);
    }

    private static final class FakeProvider implements LlmProvider {

        private LlmRequest lastRequest;

        @Override
        public LlmResponse generate(LlmRequest request) {
            if (request.options().responseFormat() == LlmResponseFormat.JSON_OBJECT) {
                throw new LlmProviderException(LlmProviderError.UNSUPPORTED_CAPABILITY);
            }
            lastRequest = request;
            return new LlmResponse("fake", null, metadata());
        }

        @Override
        public LlmStreamCall stream(LlmRequest request, Consumer<LlmStreamEvent> consumer) {
            lastRequest = request;
            consumer.accept(new LlmStreamEvent.TextDelta("fake"));
            consumer.accept(new LlmStreamEvent.Completed(metadata()));
            return new CompletedCall();
        }

        @Override
        public LlmProviderCapabilities capabilities() {
            return new LlmProviderCapabilities(true, false, false, 1024, 128);
        }

        @Override
        public void testConnection() {
        }

        private LlmRequest lastRequest() {
            return lastRequest;
        }

        private LlmResponseMetadata metadata() {
            return new LlmResponseMetadata("fake", "fake-model", "stop", 1, 1, 2, "fake-id");
        }
    }

    private static final class CompletedCall implements LlmStreamCall {

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(LlmStreamStatus.COMPLETED, null, null);
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }
}
