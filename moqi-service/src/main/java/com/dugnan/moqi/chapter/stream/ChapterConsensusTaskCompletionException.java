package com.dugnan.moqi.chapter.stream;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 表示共识任务完成时状态已被取消或并发修改。
 */
public class ChapterConsensusTaskCompletionException extends RuntimeException {

    /**
     * 创建共识任务完成竞争异常。
     */
    public ChapterConsensusTaskCompletionException() {
        super("共识任务状态已变化");
    }
}
