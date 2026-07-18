package com.dugnan.moqi.task.service.impl;

import java.time.LocalDateTime;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskCanceled;
import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskDetail;
import com.dugnan.moqi.task.service.AiTaskService;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 实现 AI 任务只读查询与幂等取消，不负责创建任务。
 */
@Service
public class AiTaskServiceImpl implements AiTaskService {

    private static final String STATUS_CANCELED = "canceled";
    private static final Set<String> NON_TERMINAL_STATUSES = Set.of("queued", "running");
    private static final Set<String> TERMINAL_STATUSES = Set.of("succeeded", "failed", STATUS_CANCELED);
    private static final Set<String> TASK_STATUSES =
            Set.of("queued", "running", "succeeded", "failed", STATUS_CANCELED);

    private final AiTaskMapper taskMapper;

    /**
     * 创建 AI 任务服务。
     *
     * @param taskMapper AI 任务数据访问对象
     */
    public AiTaskServiceImpl(AiTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public AiTaskDetail getTask(Long taskId) {
        return taskDetail(requireTask(taskId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AiTaskCanceled cancelTask(Long taskId) {
        AiTaskEntity task = requireTask(taskId);
        if (TERMINAL_STATUSES.contains(task.getTaskStatus())) {
            return cancelResult(task);
        }

        int currentVersion = task.getVersion() == null ? 0 : task.getVersion();
        LocalDateTime modifiedAt = LocalDateTime.now();
        UpdateWrapper<AiTaskEntity> update = new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", currentVersion)
                .in("task_status", NON_TERMINAL_STATUSES)
                .set("task_status", STATUS_CANCELED)
                .set("version", currentVersion + 1)
                .set("gmt_modified", modifiedAt);
        if (taskMapper.update(null, update) == 1) {
            return new AiTaskCanceled(task.getId(), STATUS_CANCELED, modifiedAt);
        }

        AiTaskEntity latest = requireTask(taskId);
        if (TERMINAL_STATUSES.contains(latest.getTaskStatus())) {
            return cancelResult(latest);
        }
        throw new BusinessException(ErrorCode.BUSINESS_ERROR, "任务状态已变化，请重试");
    }

    /**
     * 获取未删除且状态合法的任务。
     *
     * @param taskId 任务 ID
     * @return 任务实体
     */
    private AiTaskEntity requireTask(Long taskId) {
        AiTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null || Integer.valueOf(1).equals(task.getDeleted())) {
            throw new BusinessException(ErrorCode.AI_TASK_NOT_FOUND, "AI 任务不存在");
        }
        if (!TASK_STATUSES.contains(task.getTaskStatus())) {
            throw new IllegalStateException("AI 任务状态非法: " + task.getTaskStatus());
        }
        return task;
    }

    /**
     * 转换任务详情。
     *
     * @param task 任务实体
     * @return 任务详情
     */
    private AiTaskDetail taskDetail(AiTaskEntity task) {
        return new AiTaskDetail(
                task.getId(),
                task.getTaskType(),
                task.getTaskStatus(),
                task.getWorkId(),
                task.getChapterId(),
                task.getResultMessageId(),
                task.getResultGenerationId(),
                task.getErrorCode(),
                task.getErrorMessage(),
                task.getGmtCreate(),
                task.getGmtModified());
    }

    /**
     * 转换取消结果。
     *
     * @param task 任务实体
     * @return 取消结果
     */
    private AiTaskCanceled cancelResult(AiTaskEntity task) {
        return new AiTaskCanceled(task.getId(), task.getTaskStatus(), task.getGmtModified());
    }
}
