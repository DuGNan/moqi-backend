package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 定义不会泄露上游响应细节的 Provider 错误。
 */
public enum LlmProviderError {
    AUTHENTICATION("DeepSeek 鉴权失败"),
    RATE_LIMITED("DeepSeek 请求频率受限"),
    SERVICE_UNAVAILABLE("DeepSeek 服务暂不可用"),
    TIMEOUT("连接 DeepSeek 超时"),
    INVALID_RESPONSE("DeepSeek 响应格式异常"),
    REQUEST_REJECTED("请求被 DeepSeek 拒绝"),
    NETWORK("无法连接 DeepSeek");

    private final String safeMessage;

    LlmProviderError(String safeMessage) {
        this.safeMessage = safeMessage;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
