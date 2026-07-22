package com.dugnan.moqi.llm;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletion;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.Role;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 使用 Spring AI DeepSeekApi 与 DeepSeekChatModel 调用 DeepSeek。
 */
public class DeepSeekLlmProvider implements LlmProvider {

    static final int CONNECTION_TEST_MAX_TOKENS = 64;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final DeepSeekApi deepSeekApi;
    private final DeepSeekChatModel chatModel;
    private final String model;

    public DeepSeekLlmProvider(DeepSeekProviderConfig config) {
        this(config, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    DeepSeekLlmProvider(DeepSeekProviderConfig config, Duration connectTimeout, Duration readTimeout) {
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
        DeepSeekChatOptions options = DeepSeekChatOptions.builder().model(config.model()).build();
        this.chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build())
                .build();
        this.model = config.model();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        try {
            List<Message> messages = new ArrayList<>();
            if (StringUtils.hasText(request.systemPrompt())) {
                messages.add(new SystemMessage(request.systemPrompt()));
            }
            messages.add(new UserMessage(request.userPrompt()));
            DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                    .model(model)
                    .maxTokens(request.maxTokens())
                    .build();
            ChatResponse response = chatModel.call(new Prompt(messages, options));
            if (isEmptyResponse(response)) {
                throw new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
            }
            return new LlmResponse(response.getResult().getOutput().getText());
        } catch (RuntimeException exception) {
            throw mapException(exception);
        }
    }

    @Override
    public void testConnection() {
        ChatCompletionMessage message = new ChatCompletionMessage("仅回复 OK", Role.USER);
        ChatCompletionRequest request = new ChatCompletionRequest(
                List.of(message),
                model,
                null,
                CONNECTION_TEST_MAX_TOKENS,
                null,
                null,
                null,
                false,
                null,
                null,
                false,
                null,
                null,
                null);
        try {
            ResponseEntity<ChatCompletion> response = deepSeekApi.chatCompletionEntity(request);
            ChatCompletion body = response.getBody();
            if (body == null || body.choices() == null || body.choices().isEmpty()) {
                throw new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
            }
            var firstChoice = body.choices().get(0);
            if (firstChoice == null) {
                throw new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
            }
            ChatCompletionMessage answer = firstChoice.message();
            if (!hasUsableAnswer(answer)) {
                throw new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
            }
        } catch (RuntimeException exception) {
            throw mapException(exception);
        }
    }

    private LlmProviderException mapException(RuntimeException exception) {
        if (exception instanceof LlmProviderException providerException) {
            return providerException;
        }
        if (hasCause(exception, HttpTimeoutException.class)) {
            return new LlmProviderException(LlmProviderError.TIMEOUT);
        }
        if (exception instanceof ResourceAccessException) {
            return new LlmProviderException(LlmProviderError.NETWORK);
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

    private boolean isEmptyResponse(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return true;
        }
        return response.getResult().getOutput() == null
                || !StringUtils.hasText(response.getResult().getOutput().getText());
    }

    private boolean hasUsableAnswer(ChatCompletionMessage answer) {
        if (answer == null) {
            return false;
        }
        return StringUtils.hasText(answer.content())
                || StringUtils.hasText(answer.reasoningContent());
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
}
