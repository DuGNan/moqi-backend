package com.dugnan.moqi.context;

/**
 * 组装并持久化故事上下文快照的应用服务。
 *
 * @author dgn
 */
public interface StoryContextEngine {

    /**
     * 构建并持久化故事上下文快照。
     *
     * @param command 上下文构建命令
     * @return 故事上下文快照
     */
    StoryContextSnapshot build(StoryContextBuildCommand command);
}
