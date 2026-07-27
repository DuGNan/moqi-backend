package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 定义供应商无关的模型消息角色。
 */
public enum LlmRole {
    /** 系统指令。 */
    SYSTEM,
    /** 用户消息。 */
    USER,
    /** 助手历史消息。 */
    ASSISTANT,
    /** 工具消息扩展值，当前真实 Provider 不执行工具。 */
    TOOL
}
