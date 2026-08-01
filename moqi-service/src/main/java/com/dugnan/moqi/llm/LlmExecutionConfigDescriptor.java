package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 表示可安全持久化和恢复校验的模型配置版本描述。
 */
public record LlmExecutionConfigDescriptor(
        String provider,
        String model,
        Integer configVersion,
        Integer credentialVersion) {
}
