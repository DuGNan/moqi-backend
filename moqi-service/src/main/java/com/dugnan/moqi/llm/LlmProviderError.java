package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 定义不会泄露上游响应细节的 Provider 错误。
 */
public enum LlmProviderError {
    /** 鉴权失败。 */
    AUTHENTICATION("DeepSeek 鉴权失败"),
    /** 请求频率受限。 */
    RATE_LIMITED("DeepSeek 请求频率受限"),
    /** 上游服务暂不可用。 */
    SERVICE_UNAVAILABLE("DeepSeek 服务暂不可用"),
    /** 连接或读取超时。 */
    TIMEOUT("连接 DeepSeek 超时"),
    /** 上游响应结构非法或为空。 */
    INVALID_RESPONSE("DeepSeek 响应格式异常"),
    /** 其他客户端请求被拒绝。 */
    REQUEST_REJECTED("请求被 DeepSeek 拒绝"),
    /** 无法建立或维持网络连接。 */
    NETWORK("无法连接 DeepSeek"),
    /** 当前 Provider 不支持请求能力。 */
    UNSUPPORTED_CAPABILITY("当前模型供应商不支持请求的能力"),
    /** 尚未接入指定 Provider。 */
    UNSUPPORTED_PROVIDER("当前模型供应商尚未接入");

    private final String safeMessage;

    LlmProviderError(String safeMessage) {
        this.safeMessage = safeMessage;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
