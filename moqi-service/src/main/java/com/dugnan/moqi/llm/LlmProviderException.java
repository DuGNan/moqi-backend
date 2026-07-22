package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 封装仅携带安全错误分类和中文消息的 Provider 异常。
 */
public class LlmProviderException extends RuntimeException {

    private final LlmProviderError error;

    public LlmProviderException(LlmProviderError error) {
        super(error.safeMessage());
        this.error = error;
    }

    public LlmProviderError getError() {
        return error;
    }
}
