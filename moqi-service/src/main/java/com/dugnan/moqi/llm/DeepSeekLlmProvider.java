package com.dugnan.moqi.llm;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletion;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionChunk;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionFinishReason;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.Role;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionRequest;
import org.springframework.ai.deepseek.api.DeepSeekApi.Usage;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.Disposable;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 使用现有 Spring AI DeepSeekApi 实现 Provider V2 与可取消流式调用。
 */
public class DeepSeekLlmProvider implements LlmProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeepSeekLlmProvider.class);

    static final int CONNECTION_TEST_MAX_TOKENS = 64;
    private static final int MAX_STOP_SEQUENCES = 16;
    private static final int INVALID_RESPONSE_PREVIEW_LENGTH = 64;
    private static final int INVALID_RESPONSE_PREVIEW_PART_COUNT = 2;
    private static final int MAX_CONTEXT_TOKENS = 1_000_000;
    private static final int MAX_OUTPUT_TOKENS = 384_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final LlmProviderCapabilities CAPABILITIES =
            new LlmProviderCapabilities(true, true, false, MAX_CONTEXT_TOKENS, MAX_OUTPUT_TOKENS);

    private final DeepSeekApi deepSeekApi;
    private final String model;

    /**
     * 使用默认连接与读取超时创建 DeepSeek Provider。
     *
     * @param config 供应商运行时配置
     */
    public DeepSeekLlmProvider(LlmProviderRuntimeConfig config) {
        this(config, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    DeepSeekLlmProvider(
            LlmProviderRuntimeConfig config,
            Duration connectTimeout,
            Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        this.deepSeekApi = DeepSeekApi.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .restClientBuilder(restClientBuilder)
                .responseErrorHandler(new DeepSeekResponseErrorHandler())
                .build();
        this.model = config.model();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        try {
            ResponseEntity<ChatCompletion> response =
                    deepSeekApi.chatCompletionEntity(apiRequest(request, false));
            ChatCompletion body = response.getBody();
            if (body == null || body.choices() == null || body.choices().isEmpty()) {
                logInvalidStructuredResponse("empty_choices", null);
                throw new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
            }
            ChatCompletion.Choice choice = body.choices().get(0);
            if (choice == null || choice.message() == null
                    || !StringUtils.hasText(choice.message().content())) {
                logInvalidStructuredResponse("empty_content", null);
                throw new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
            }
            String content = choice.message().content();
            JsonNode structuredContent = structuredContent(request, content);
            return new LlmResponse(
                    content,
                    structuredContent,
                    metadata(body.id(), body.model(), choice.finishReason(), body.usage()));
        } catch (RuntimeException exception) {
            throw mapException(exception);
        }
    }

    @Override
    public LlmStreamCall stream(LlmRequest request, Consumer<LlmStreamEvent> consumer) {
        if (consumer == null) {
            throw new IllegalArgumentException("流式事件消费者不能为空");
        }
        try {
            DeepSeekStreamCall call = new DeepSeekStreamCall(consumer);
            Disposable disposable = deepSeekApi.chatCompletionStream(apiRequest(request, true))
                    .subscribe(call::onChunk, call::onError, call::onComplete);
            call.bind(disposable);
            return call;
        } catch (RuntimeException exception) {
            throw mapException(exception);
        }
    }

    @Override
    public LlmProviderCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public void testConnection() {
        LlmRequest request = new LlmRequest(
                List.of(new LlmMessage(LlmRole.USER, "仅回复 OK")),
                new LlmOptions(CONNECTION_TEST_MAX_TOKENS, null, List.of(), LlmResponseFormat.TEXT));
        try {
            ResponseEntity<ChatCompletion> response =
                    deepSeekApi.chatCompletionEntity(apiRequest(request, false));
            ChatCompletion body = response.getBody();
            if (body == null || body.choices() == null || body.choices().isEmpty()) {
                throw new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
            }
            ChatCompletion.Choice choice = body.choices().get(0);
            if (!hasConnectionTestContent(choice)) {
                throw new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
            }
        } catch (RuntimeException exception) {
            throw mapException(exception);
        }
    }

    private boolean hasConnectionTestContent(ChatCompletion.Choice choice) {
        if (choice == null || choice.message() == null) {
            return false;
        }
        return StringUtils.hasText(choice.message().content())
                || StringUtils.hasText(choice.message().reasoningContent());
    }

    private ChatCompletionRequest apiRequest(LlmRequest request, boolean isStream) {
        if (request.options().stopSequences().size() > MAX_STOP_SEQUENCES) {
            throw new LlmProviderException(LlmProviderError.REQUEST_REJECTED);
        }
        List<ChatCompletionMessage> messages = new ArrayList<>();
        for (LlmMessage message : request.messages()) {
            if (message.role() == LlmRole.TOOL) {
                throw new LlmProviderException(LlmProviderError.UNSUPPORTED_CAPABILITY);
            }
            messages.add(new ChatCompletionMessage(message.content(), role(message.role())));
        }
        LlmOptions options = request.options();
        return new ChatCompletionRequest(
                messages,
                model,
                null,
                options.maxOutputTokens(),
                null,
                responseFormat(options.responseFormat()),
                options.stopSequences().isEmpty() ? null : options.stopSequences(),
                isStream,
                options.temperature(),
                null,
                null,
                null,
                null,
                null);
    }

    private Role role(LlmRole role) {
        return Role.valueOf(role.name());
    }

    private ResponseFormat responseFormat(LlmResponseFormat responseFormat) {
        ResponseFormat.Type type = responseFormat == LlmResponseFormat.JSON_OBJECT
                ? ResponseFormat.Type.JSON_OBJECT
                : ResponseFormat.Type.TEXT;
        return ResponseFormat.builder().type(type).build();
    }

    private JsonNode structuredContent(LlmRequest request, String content) {
        if (request.options().responseFormat() != LlmResponseFormat.JSON_OBJECT) {
            return null;
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(content);
            if (parsed == null || !parsed.isObject()) {
                logInvalidStructuredResponse("not_json_object", content);
                throw new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            logInvalidStructuredResponse(
                    "json_parse_error:" + exception.getClass().getSimpleName(), content);
            throw new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
        }
    }

    private static void logInvalidStructuredResponse(String reason, String content) {
        LOGGER.warn("DeepSeek structured response invalid: reason={}, {}", reason,
                summarizeInvalidJsonContent(content));
    }

    static String summarizeInvalidJsonContent(String content) {
        if (!StringUtils.hasText(content)) {
            return "content=empty";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length()
                <= INVALID_RESPONSE_PREVIEW_LENGTH * INVALID_RESPONSE_PREVIEW_PART_COUNT) {
            return "length=" + content.length() + ", preview=" + normalized;
        }
        return "length=" + content.length()
                + ", head=" + normalized.substring(0, INVALID_RESPONSE_PREVIEW_LENGTH)
                + ", tail=" + normalized.substring(normalized.length() - INVALID_RESPONSE_PREVIEW_LENGTH);
    }

    private LlmResponseMetadata metadata(
            String requestId,
            String responseModel,
            ChatCompletionFinishReason finishReason,
            Usage usage) {
        return new LlmResponseMetadata(
                "deepseek",
                StringUtils.hasText(responseModel) ? responseModel : model,
                finishReason == null ? null : finishReason.name().toLowerCase(Locale.ROOT),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.totalTokens(),
                requestId);
    }

    private LlmProviderException mapException(Throwable exception) {
        if (exception instanceof LlmProviderException providerException) {
            return providerException;
        }
        if (hasCause(exception, HttpTimeoutException.class)) {
            return new LlmProviderException(LlmProviderError.TIMEOUT);
        }
        if (exception instanceof ResourceAccessException) {
            return new LlmProviderException(LlmProviderError.NETWORK);
        }
        if (exception instanceof WebClientResponseException responseException) {
            return statusError(responseException.getStatusCode().value());
        }
        if (exception instanceof HttpMessageConversionException
                || hasCause(exception, JsonProcessingException.class)) {
            return new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
        }
        if (exception instanceof RestClientException) {
            return new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
        }
        return new LlmProviderException(LlmProviderError.NETWORK);
    }

    private LlmProviderException statusError(int status) {
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
            return new LlmProviderException(LlmProviderError.AUTHENTICATION);
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return new LlmProviderException(LlmProviderError.RATE_LIMITED);
        }
        if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return new LlmProviderException(LlmProviderError.SERVICE_UNAVAILABLE);
        }
        return new LlmProviderException(LlmProviderError.REQUEST_REJECTED);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private final class DeepSeekStreamCall implements LlmStreamCall {

        private final Consumer<LlmStreamEvent> consumer;
        private final CountDownLatch completion = new CountDownLatch(1);
        private final AtomicReference<Disposable> disposable = new AtomicReference<>();
        private final AtomicReference<LlmStreamStatus> status =
                new AtomicReference<>(LlmStreamStatus.RUNNING);
        private final AtomicReference<LlmResponseMetadata> finalMetadata = new AtomicReference<>();
        private final AtomicReference<LlmProviderError> error = new AtomicReference<>();
        private final AtomicBoolean hasText = new AtomicBoolean(false);

        private String requestId;
        private String responseModel;
        private ChatCompletionFinishReason finishReason;
        private Usage usage;

        private DeepSeekStreamCall(Consumer<LlmStreamEvent> consumer) {
            this.consumer = consumer;
        }

        private void bind(Disposable subscription) {
            disposable.set(subscription);
            if (status.get() == LlmStreamStatus.CANCELED) {
                subscription.dispose();
            }
        }

        private void onChunk(ChatCompletionChunk chunk) {
            if (status.get() != LlmStreamStatus.RUNNING || chunk == null) {
                return;
            }
            requestId = chunk.id();
            responseModel = chunk.model();
            if (chunk.usage() != null) {
                usage = chunk.usage();
            }
            if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                ChatCompletionChunk.ChunkChoice choice = chunk.choices().get(0);
                if (choice != null) {
                    if (choice.finishReason() != null) {
                        finishReason = choice.finishReason();
                    }
                    if (choice.delta() != null && StringUtils.hasText(choice.delta().content())) {
                        hasText.set(true);
                        consumer.accept(new LlmStreamEvent.TextDelta(choice.delta().content()));
                    }
                }
            }
            if (usage != null || finishReason != null) {
                LlmResponseMetadata current = currentMetadata();
                finalMetadata.set(current);
                consumer.accept(new LlmStreamEvent.Metadata(current));
            }
        }

        private void onError(Throwable throwable) {
            if (!status.compareAndSet(LlmStreamStatus.RUNNING, LlmStreamStatus.FAILED)) {
                return;
            }
            error.set(mapException(throwable).getError());
            completion.countDown();
        }

        private void onComplete() {
            if (!hasText.get()) {
                onError(new LlmProviderException(LlmProviderError.INVALID_RESPONSE));
                return;
            }
            if (!status.compareAndSet(LlmStreamStatus.RUNNING, LlmStreamStatus.COMPLETED)) {
                return;
            }
            LlmResponseMetadata current = currentMetadata();
            finalMetadata.set(current);
            try {
                consumer.accept(new LlmStreamEvent.Completed(current));
            } finally {
                completion.countDown();
            }
        }

        private LlmResponseMetadata currentMetadata() {
            return metadata(requestId, responseModel, finishReason, usage);
        }

        @Override
        public boolean cancel() {
            if (!status.compareAndSet(LlmStreamStatus.RUNNING, LlmStreamStatus.CANCELED)) {
                return false;
            }
            Disposable subscription = disposable.get();
            if (subscription != null) {
                subscription.dispose();
            }
            completion.countDown();
            return true;
        }

        @Override
        public LlmStreamResult await() {
            try {
                completion.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cancel();
            }
            return new LlmStreamResult(status.get(), finalMetadata.get(), error.get());
        }

        @Override
        public boolean isDone() {
            return status.get() != LlmStreamStatus.RUNNING;
        }
    }
}
