package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 封装仅携带安全错误分类和中文消息的 Provider 异常。
 */
public class LlmProviderException extends RuntimeException {

    private final LlmProviderError error;

    /**
     * 使用安全 Provider 错误分类创建异常。
     *
     * @param error Provider 错误分类
     */
    public LlmProviderException(LlmProviderError error) {
        super(error.safeMessage());
        this.error = error;
    }

    /**
     * 获取 Provider 错误分类。
     *
     * @return Provider 错误分类
     */
    public LlmProviderError getError() {
        return error;
    }
}
