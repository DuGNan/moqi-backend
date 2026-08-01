package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 封装一次模型执行的短生命周期敏感配置与可持久化安全描述。
 */
public record LlmExecutionConfig(
        LlmProviderRuntimeConfig runtimeConfig,
        LlmExecutionConfigDescriptor descriptor) {
}
