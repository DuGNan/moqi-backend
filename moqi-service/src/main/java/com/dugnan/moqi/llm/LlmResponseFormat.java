package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 定义模型响应的通用格式约束。
 */
public enum LlmResponseFormat {
    /** 普通文本响应。 */
    TEXT,
    /** JSON 对象响应。 */
    JSON_OBJECT
}
