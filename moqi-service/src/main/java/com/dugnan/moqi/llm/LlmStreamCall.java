package com.dugnan.moqi.llm;

/**
 * @author dgn
 * @date 2026-07-27
 * @description 提供框架无关的流式调用取消和终态等待能力。
 */
public interface LlmStreamCall {

    /**
     * 尝试取消上游调用。
     *
     * @return 本次调用是否赢得终态竞争
     */
    boolean cancel();

    /**
     * 等待调用进入终态。
     *
     * @return 流式调用结果
     */
    LlmStreamResult await();

    /**
     * 判断调用是否已经进入终态。
     *
     * @return 是否完成
     */
    boolean isDone();
}
