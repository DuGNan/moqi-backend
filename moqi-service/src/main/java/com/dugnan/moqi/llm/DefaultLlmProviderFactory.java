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

    private final LlmCallObservationService observationService;

    public DefaultLlmProviderFactory(LlmCallObservationService observationService) {
        this.observationService = observationService;
    }

    @Override
    public LlmProvider create(LlmProviderRuntimeConfig config) {
        if (!DEEPSEEK_PROVIDER.equals(config.provider())) {
            throw new LlmProviderException(LlmProviderError.UNSUPPORTED_PROVIDER);
        }
        return new DeepSeekLlmProvider(config);
    }

    @Override
    public LlmProvider createObserved(LlmExecutionConfig config, LlmCallContext context) {
        if (config == null || context == null) {
            throw new IllegalArgumentException("模型执行配置和调用上下文不能为空");
        }
        return new ObservedLlmProvider(create(config.runtimeConfig()), config, context, observationService);
    }
}
