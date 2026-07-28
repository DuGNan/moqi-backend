package com.dugnan.moqi.task.service.impl;

import java.time.LocalDateTime;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.stream.ChapterReplyEvent;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskCanceled;
import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskDetail;
import com.dugnan.moqi.task.event.AiTaskCancellationSignal;
import com.dugnan.moqi.task.service.AiTaskService;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 实现 AI 任务只读查询与幂等取消，不负责创建任务。
 */
@Service
public class AiTaskServiceImpl implements AiTaskService {

    private static final String TASK_TYPE_CONVERSATION_REPLY = "conversation_reply";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_CANCELED = "canceled";
    private static final Set<String> NON_TERMINAL_STATUSES = Set.of("queued", STATUS_RUNNING);
    private static final Set<String> TERMINAL_STATUSES = Set.of("succeeded", "failed", STATUS_CANCELED);
    private static final Set<String> TASK_STATUSES =
            Set.of("queued", "running", "succeeded", "failed", STATUS_CANCELED);
    private static final int MAX_CANCEL_ATTEMPTS = 3;

    private final AiTaskMapper taskMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建 AI 任务服务。
     *
     * @param taskMapper AI 任务数据访问对象
     */
    public AiTaskServiceImpl(AiTaskMapper taskMapper, ApplicationEventPublisher eventPublisher) {
        this.taskMapper = taskMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public AiTaskDetail getTask(Long taskId) {
        return taskDetail(requireTask(taskId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class, isolation = Isolation.READ_COMMITTED)
    public AiTaskCanceled cancelTask(Long taskId) {
        AiTaskEntity task = requireTask(taskId);
        for (int attempt = 0; attempt < MAX_CANCEL_ATTEMPTS; attempt++) {
            if (TERMINAL_STATUSES.contains(task.getTaskStatus())) {
                return cancelResult(task);
            }
            AiTaskCanceled canceled = tryCancel(task);
            if (canceled != null) {
                publishProviderCancellation(task);
                publishCanceled(task);
                return canceled;
            }
            task = requireTask(taskId);
        }
        if (TERMINAL_STATUSES.contains(task.getTaskStatus())) {
            return cancelResult(task);
        }
        throw new BusinessException(ErrorCode.AI_TASK_STATE_CONFLICT, "任务状态已变化，请重试");
    }

    /**
     * 使用任务快照版本尝试一次原子取消。
     *
     * @param task 非终态任务快照
     * @return 取消结果，竞争失败时返回 null
     */
    private AiTaskCanceled tryCancel(AiTaskEntity task) {
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
        return null;
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
                task.getResultBriefId(),
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

    private void publishCanceled(AiTaskEntity task) {
        if (TASK_TYPE_CONVERSATION_REPLY.equals(task.getTaskType())) {
            eventPublisher.publishEvent(ChapterReplyEvent.canceled(task.getChapterId(), task.getId()));
        }
    }

    private void publishProviderCancellation(AiTaskEntity task) {
        if (STATUS_RUNNING.equals(task.getTaskStatus())) {
            eventPublisher.publishEvent(new AiTaskCancellationSignal(task.getId()));
        }
    }
}
