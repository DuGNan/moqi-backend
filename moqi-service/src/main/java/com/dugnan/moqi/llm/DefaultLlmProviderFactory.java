package com.dugnan.moqi.llm;

import org.springframework.stereotype.Component;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 每次按配置快照创建新的 DeepSeek Provider。
 */
@Component
public class DefaultLlmProviderFactory implements LlmProviderFactory {

    @Override
    public LlmProvider create(DeepSeekProviderConfig config) {
        return new DeepSeekLlmProvider(config);
    }
}
