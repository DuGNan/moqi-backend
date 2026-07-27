package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 定义流式调用的运行与终态。
 */
public enum LlmStreamStatus {
    /** 正在接收上游响应。 */
    RUNNING,
    /** 上游正常完成。 */
    COMPLETED,
    /** 调用方取消。 */
    CANCELED,
    /** 上游或协议失败。 */
    FAILED
}
