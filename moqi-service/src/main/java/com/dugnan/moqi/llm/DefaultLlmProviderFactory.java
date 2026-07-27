package com.dugnan.moqi.llm;

import org.springframework.stereotype.Component;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 根据供应商无关配置动态创建 Provider。
 */
@Component
public class DefaultLlmProviderFactory implements LlmProviderFactory {

    private static final String DEEPSEEK_PROVIDER = "deepseek";

    @Override
    public LlmProvider create(LlmProviderRuntimeConfig config) {
        if (!DEEPSEEK_PROVIDER.equals(config.provider())) {
            throw new LlmProviderException(LlmProviderError.UNSUPPORTED_PROVIDER);
        }
        return new DeepSeekLlmProvider(config);
    }
}
