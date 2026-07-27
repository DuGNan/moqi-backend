package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 保存最小作用域内创建 Provider 所需的供应商无关敏感快照。
 */
public final class LlmProviderRuntimeConfig {

    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public LlmProviderRuntimeConfig(String provider, String baseUrl, String apiKey, String model) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public String provider() {
        return provider;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    @Override
    public String toString() {
        return "LlmProviderRuntimeConfig[provider=" + provider
                + ", baseUrl=" + baseUrl
                + ", apiKey=****, model=" + model + "]";
    }
}
