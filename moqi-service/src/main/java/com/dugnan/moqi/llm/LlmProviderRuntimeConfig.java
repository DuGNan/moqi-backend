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

    /**
     * 创建最小作用域的 Provider 敏感配置快照。
     *
     * @param provider 供应商标识
     * @param baseUrl API 基础地址
     * @param apiKey API 凭据
     * @param model 模型标识
     */
    public LlmProviderRuntimeConfig(String provider, String baseUrl, String apiKey, String model) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 获取供应商标识。
     *
     * @return 供应商标识
     */
    public String provider() {
        return provider;
    }

    /**
     * 获取 API 基础地址。
     *
     * @return API 基础地址
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 获取 API 凭据。
     *
     * @return API 凭据
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * 获取模型标识。
     *
     * @return 模型标识
     */
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
