package com.dugnan.moqi.context;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 为可恢复工作流按明确标识读取已持久化故事上下文快照。
 */
public interface StoryContextSnapshotQueryPort {

    /**
     * 读取一个不可变故事上下文快照。
     *
     * @param snapshotId 快照 ID
     * @return 快照
     */
    StoryContextSnapshot load(Long snapshotId);
}
