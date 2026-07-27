package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 按当前数据库配置动态创建 Provider，不缓存密钥客户端。
 */
public interface LlmProviderFactory {

    /**
     * 按不可变配置快照创建新的 Provider。
     *
     * @param config 供应商无关配置快照
     * @return 新 Provider
     */
    LlmProvider create(LlmProviderRuntimeConfig config);
}
