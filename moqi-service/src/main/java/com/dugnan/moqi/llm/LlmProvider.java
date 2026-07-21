package com.dugnan.moqi.llm;

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
     * 发送最小请求验证当前 Provider 可用性。
     */
    void testConnection();
}
