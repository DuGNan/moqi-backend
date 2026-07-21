package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 提供创建单次 DeepSeek 客户端所需的配置快照。
 */
public record DeepSeekProviderConfig(String baseUrl, String apiKey, String model) {
}
