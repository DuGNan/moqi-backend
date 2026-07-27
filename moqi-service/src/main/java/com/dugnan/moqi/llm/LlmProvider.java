package com.dugnan.moqi.llm;

import java.util.function.Consumer;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 定义可替换的大模型调用边界。
 */
public interface LlmProvider {

    /**
     * 生成模型回复。
     *
     * @param request 模型请求
     * @return 模型回复
     */
    LlmResponse generate(LlmRequest request);

    /**
     * 流式生成模型回复。
     *
     * @param request 模型请求
     * @param consumer 文本增量消费者
     * @return 可取消并可等待终态的调用句柄
     */
    LlmStreamCall stream(LlmRequest request, Consumer<LlmStreamEvent> consumer);

    /**
     * 返回当前实现真实接入的能力。
     *
     * @return Provider 能力
     */
    LlmProviderCapabilities capabilities();

    /**
     * 发送最小请求验证当前 Provider 可用性。
     */
    void testConnection();
}
