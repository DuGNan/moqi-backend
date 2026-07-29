package com.dugnan.moqi.context;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;

/**
 * 在同一短事务内创建上下文快照并绑定 AI 任务。
 *
 * @author dgn
 */
@Service
public class StoryContextTaskBindingService {

    private static final String TASK_STATUS_RUNNING = "running";

    private final StoryContextEngine contextEngine;
    private final AiTaskMapper taskMapper;

    /**
     * 创建上下文快照绑定服务。
     *
     * @param contextEngine 故事上下文引擎
     * @param taskMapper AI 任务数据访问对象
     */
    public StoryContextTaskBindingService(StoryContextEngine contextEngine, AiTaskMapper taskMapper) {
        this.contextEngine = contextEngine;
        this.taskMapper = taskMapper;
    }

    /**
     * 创建快照并以任务版本条件绑定。
     *
     * @param command 上下文构建命令
     * @param task 正在运行的 AI 任务
     * @return 已绑定的上下文快照
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public StoryContextSnapshot buildAndAttach(StoryContextBuildCommand command, AiTaskEntity task) {
        StoryContextSnapshot snapshot = contextEngine.build(command);
        int version = task.getVersion() == null ? 0 : task.getVersion();
        int updated = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .eq("task_status", TASK_STATUS_RUNNING)
                .set("context_snapshot_id", snapshot.id())
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (updated != 1) {
            throw new StoryContextTaskBindingException();
        }
        task.setContextSnapshotId(snapshot.id());
        task.setVersion(version + 1);
        return snapshot;
    }
}
