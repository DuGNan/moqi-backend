package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 按当前数据库配置动态创建 Provider，不缓存密钥客户端。
 */
public interface LlmProviderFactory {

    LlmProvider create(DeepSeekProviderConfig config);
}
