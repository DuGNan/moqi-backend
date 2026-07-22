package com.dugnan.moqi.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 使用本机 HTTP 服务验证真实 DeepSeekApi 请求和安全错误映射。
 */
class DeepSeekLlmProviderTest {

    private static final String TEST_API_KEY = "test-only-provider-key";
    private static final String MODEL = "deepseek-v4-flash";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 验证路径、Bearer、模型、64 token 和普通 content 成功响应。
     */
    @Test
    void sendsExpectedConnectionTestRequestAndAcceptsContent() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(OBJECT_MAPPER.readTree(exchange.getRequestBody()));
            json(exchange, 200, completion("OK", null));
        });

        provider(Duration.ofSeconds(2)).testConnection();

        assertThat(authorization.get()).isEqualTo("Bearer " + TEST_API_KEY);
        assertThat(requestBody.get().get("model").asText()).isEqualTo(MODEL);
        assertThat(requestBody.get().get("max_tokens").asInt()).isEqualTo(64);
        assertThat(requestBody.get().get("messages").get(0).get("content").asText())
                .isEqualTo("仅回复 OK");
    }

    /**
     * 验证只有 reasoning_content 时也视为合法响应。
     */
    @Test
    void acceptsReasoningContent() throws Exception {
        start(exchange -> json(exchange, 200, completion(null, "OK")));

        provider(Duration.ofSeconds(2)).testConnection();
    }

    /**
     * 验证 HTTP 状态只映射为固定安全错误。
     */
    @Test
    void mapsHttpStatusToSafeErrors() throws Exception {
        assertStatusError(401, LlmProviderError.AUTHENTICATION);
        assertStatusError(403, LlmProviderError.AUTHENTICATION);
        assertStatusError(429, LlmProviderError.RATE_LIMITED);
        assertStatusError(400, LlmProviderError.REQUEST_REJECTED);
        assertStatusError(503, LlmProviderError.SERVICE_UNAVAILABLE);
    }

    /**
     * 验证非法 JSON 和空内容映射为响应格式异常。
     */
    @Test
    void mapsMalformedOrEmptyResponseToInvalidResponse() throws Exception {
        start(exchange -> json(exchange, 200, "not-json"));
        assertProviderError(provider(Duration.ofSeconds(2)), LlmProviderError.INVALID_RESPONSE);
        stopServer();
        server = null;

        start(exchange -> json(exchange, 200, completion("", "")));
        assertProviderError(provider(Duration.ofSeconds(2)), LlmProviderError.INVALID_RESPONSE);
        stopServer();
        server = null;

        start(exchange -> json(exchange, 200,
                "{\"id\":\"test\",\"object\":\"chat.completion\","
                        + "\"created\":1,\"model\":\"deepseek-v4-flash\",\"choices\":[null]}"));
        assertProviderError(provider(Duration.ofSeconds(2)), LlmProviderError.INVALID_RESPONSE);
    }

    /**
     * 验证读取超时不会暴露底层 URL 或密钥。
     */
    @Test
    void mapsReadTimeoutToSafeTimeout() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(500);
                json(exchange, 200, completion("OK", null));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        assertProviderError(provider(Duration.ofMillis(50)), LlmProviderError.TIMEOUT);
    }

    /**
     * 验证未建立 HTTP 响应的断连映射为网络错误。
     */
    @Test
    void mapsDisconnectedServerToNetworkError() throws Exception {
        start(HttpExchange::close);

        assertProviderError(provider(Duration.ofSeconds(2)), LlmProviderError.NETWORK);
    }

    private void assertStatusError(int status, LlmProviderError expected) throws Exception {
        start(exchange -> json(exchange, status, "upstream-body-must-not-leak"));
        assertProviderError(provider(Duration.ofSeconds(2)), expected);
        stopServer();
        server = null;
    }

    private void assertProviderError(DeepSeekLlmProvider provider, LlmProviderError expected) {
        assertThatThrownBy(provider::testConnection)
                .isInstanceOf(LlmProviderException.class)
                .satisfies(throwable -> {
                    LlmProviderException exception = (LlmProviderException) throwable;
                    assertThat(exception.getError()).isEqualTo(expected);
                    assertThat(exception.getMessage()).isEqualTo(expected.safeMessage());
                    assertThat(exception.getMessage()).doesNotContain(TEST_API_KEY, "upstream-body");
                });
    }

    private DeepSeekLlmProvider provider(Duration readTimeout) {
        DeepSeekProviderConfig config = new DeepSeekProviderConfig(baseUrl(), TEST_API_KEY, MODEL);
        return new DeepSeekLlmProvider(config, Duration.ofSeconds(1), readTimeout);
    }

    private void start(ThrowingHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception exception) {
                exchange.close();
            }
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String completion(String content, String reasoningContent) throws Exception {
        var root = OBJECT_MAPPER.createObjectNode();
        root.put("id", "test-response");
        root.put("object", "chat.completion");
        root.put("created", 1);
        root.put("model", MODEL);
        var message = root.putArray("choices").addObject()
                .put("index", 0)
                .put("finish_reason", "stop")
                .putObject("message")
                .put("role", "assistant");
        if (content == null) {
            message.putNull("content");
        } else {
            message.put("content", content);
        }
        if (reasoningContent != null) {
            message.put("reasoning_content", reasoningContent);
        }
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
