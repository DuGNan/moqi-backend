package com.dugnan.moqi.task.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.policy.ConversationReplyTaskInputV1;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.mapper.ChapterOutlineCandidateMapper;
import com.dugnan.moqi.chapter.entity.ChapterOutlineCandidateEntity;
import com.dugnan.moqi.chapter.stream.ChapterReplyEvent;
import com.dugnan.moqi.chapter.stream.ConversationReplyTaskSubmittedEvent;
import com.dugnan.moqi.chapter.stream.OutlineCandidateEvent;
import com.dugnan.moqi.agent.entity.AgentRunEntity;
import com.dugnan.moqi.agent.mapper.AgentRunMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.api.PublicFailureFactory;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskCanceled;
import com.dugnan.moqi.task.dto.AiTaskModels.AiTaskDetail;
import com.dugnan.moqi.task.dto.AiTaskModels.EffectiveReplyPolicy;
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
    private static final String TASK_TYPE_OUTLINE_CANDIDATE = "outline_adjustment_candidate";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELING = "canceling";
    private static final String STATUS_CANCELED = "canceled";
    private static final String MESSAGE_ROLE_USER = "user";
    private static final Set<String> NON_TERMINAL_STATUSES = Set.of(STATUS_QUEUED, STATUS_RUNNING, STATUS_CANCELING);
    private static final Set<String> CANCELABLE_STATUSES = Set.of(STATUS_QUEUED, STATUS_RUNNING);
    private static final Set<String> TERMINAL_STATUSES = Set.of("succeeded", "failed", STATUS_CANCELED);
    private static final Set<String> TASK_STATUSES =
            Set.of(STATUS_QUEUED, STATUS_RUNNING, STATUS_CANCELING, "succeeded", STATUS_FAILED, STATUS_CANCELED);
    private static final int MAX_CANCEL_ATTEMPTS = 3;

    private final AiTaskMapper taskMapper;
    private final ChapterConversationMessageMapper messageMapper;
    private final ApplicationEventPublisher eventPublisher;

    private final ChapterOutlineCandidateMapper candidateMapper;

    private final AgentRunMapper agentRunMapper;

    /**
     * 创建 AI 任务服务。
     *
     * @param taskMapper AI 任务数据访问对象
     * @param eventPublisher 应用事件发布器
     */
    public AiTaskServiceImpl(AiTaskMapper taskMapper, ApplicationEventPublisher eventPublisher) {
        this(taskMapper, null, null, null, eventPublisher);
    }

    /** 兼容既有候选任务测试和调用方。 */
    public AiTaskServiceImpl(
            AiTaskMapper taskMapper,
            ChapterOutlineCandidateMapper candidateMapper,
            ApplicationEventPublisher eventPublisher) {
        this(taskMapper, candidateMapper, null, null, eventPublisher);
    }

    /**
     * 创建支持候选任务取消状态同步的 AI 任务服务。
     *
     * @param taskMapper AI 任务数据访问对象
     * @param candidateMapper 大纲候选数据访问对象
     * @param eventPublisher 应用事件发布器
     */
    @Autowired
    public AiTaskServiceImpl(
            AiTaskMapper taskMapper,
            ChapterOutlineCandidateMapper candidateMapper,
            AgentRunMapper agentRunMapper,
            ChapterConversationMessageMapper messageMapper,
            ApplicationEventPublisher eventPublisher) {
        this.taskMapper = taskMapper;
        this.candidateMapper = candidateMapper;
        this.agentRunMapper = agentRunMapper;
        this.messageMapper = messageMapper;
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
            if (STATUS_CANCELING.equals(task.getTaskStatus())) {
                return cancelResult(task);
            }
            AiTaskCanceled canceled = tryCancel(task);
            if (canceled != null) {
                if (STATUS_CANCELED.equals(canceled.taskStatus())) {
                    markCandidateCanceled(task);
                }
                publishProviderCancellation(task);
                publishCancellationState(task, canceled.taskStatus());
                return canceled;
            }
            task = requireTask(taskId);
        }
        if (TERMINAL_STATUSES.contains(task.getTaskStatus())) {
            return cancelResult(task);
        }
        throw new BusinessException(ErrorCode.AI_TASK_STATE_CONFLICT, "任务状态已变化，请重试");
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class, isolation = Isolation.READ_COMMITTED)
    public AiTaskDetail retryTask(Long taskId) {
        AiTaskEntity task = requireTask(taskId);
        if (NON_TERMINAL_STATUSES.contains(task.getTaskStatus())) {
            return taskDetail(task);
        }
        if (!TASK_TYPE_CONVERSATION_REPLY.equals(task.getTaskType())) {
            throw new BusinessException(ErrorCode.AI_TASK_STATE_CONFLICT, "当前 AI 任务不支持重试");
        }
        if (STATUS_CANCELED.equals(task.getTaskStatus())) {
            return retryCanceledConversationReply(task);
        }
        if (!STATUS_FAILED.equals(task.getTaskStatus())) {
            throw new BusinessException(ErrorCode.AI_TASK_STATE_CONFLICT, "当前 AI 任务不支持重试");
        }
        requireConversationReplyInput(task);
        if (tryRetry(task)) {
            eventPublisher.publishEvent(new ConversationReplyTaskSubmittedEvent(task.getId()));
            return taskDetail(task);
        }
        AiTaskEntity latest = requireTask(taskId);
        if (NON_TERMINAL_STATUSES.contains(latest.getTaskStatus())) {
            return taskDetail(latest);
        }
        throw new BusinessException(ErrorCode.AI_TASK_STATE_CONFLICT, "AI 任务状态已变化，请重试");
    }

    private boolean tryRetry(AiTaskEntity task) {
        int currentVersion = task.getVersion() == null ? 0 : task.getVersion();
        LocalDateTime modifiedAt = LocalDateTime.now();
        int updated = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", currentVersion)
                .eq("task_type", TASK_TYPE_CONVERSATION_REPLY)
                .eq("task_status", STATUS_FAILED)
                .set("task_status", STATUS_QUEUED)
                .set("result_message_id", null)
                .set("error_code", null)
                .set("error_message", null)
                .set("version", currentVersion + 1)
                .set("gmt_modified", modifiedAt));
        if (updated != 1) {
            return false;
        }
        task.setTaskStatus(STATUS_QUEUED);
        task.setResultMessageId(null);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setVersion(currentVersion + 1);
        task.setGmtModified(modifiedAt);
        return true;
    }

    private AiTaskDetail retryCanceledConversationReply(AiTaskEntity source) {
        AiTaskEntity existing = findRetryTask(source.getId());
        if (existing != null) {
            return taskDetail(existing);
        }
        requireConversationReplyInput(source);
        AiTaskEntity retry = new AiTaskEntity();
        retry.setTaskType(source.getTaskType());
        retry.setTaskStatus(STATUS_QUEUED);
        retry.setRetryOfTaskId(source.getId());
        retry.setWorkId(source.getWorkId());
        retry.setChapterId(source.getChapterId());
        retry.setTaskInputJson(source.getTaskInputJson());
        retry.setDeleted(0);
        retry.setVersion(0);
        try {
            taskMapper.insert(retry);
        } catch (DuplicateKeyException exception) {
            AiTaskEntity concurrent = findRetryTask(source.getId());
            if (concurrent != null) {
                return taskDetail(concurrent);
            }
            throw exception;
        }
        eventPublisher.publishEvent(new ConversationReplyTaskSubmittedEvent(retry.getId()));
        return taskDetail(retry);
    }

    private AiTaskEntity findRetryTask(Long sourceTaskId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<AiTaskEntity>()
                .eq(AiTaskEntity::getRetryOfTaskId, sourceTaskId)
                .eq(AiTaskEntity::getDeleted, 0));
    }

    private void requireConversationReplyInput(AiTaskEntity task) {
        if (messageMapper == null) {
            return;
        }
        Long messageId = taskInputMessageId(task);
        if (messageId != null) {
            ChapterConversationMessageEntity message = messageMapper.selectById(messageId);
            if (message != null && MESSAGE_ROLE_USER.equals(message.getMessageRole())
                    && !Integer.valueOf(1).equals(message.getDeleted())) {
                return;
            }
            throw new BusinessException(ErrorCode.AI_TASK_STATE_CONFLICT, "AI 任务快照引用的用户消息不存在");
        }
        List<ChapterConversationMessageEntity> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ChapterConversationMessageEntity>()
                        .eq(ChapterConversationMessageEntity::getAiTaskId, task.getId())
                        .eq(ChapterConversationMessageEntity::getMessageRole, MESSAGE_ROLE_USER)
                        .eq(ChapterConversationMessageEntity::getDeleted, 0)
                        .last("limit 1"));
        if (messages.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_TASK_STATE_CONFLICT, "AI 任务缺少可重试的用户消息");
        }
    }

    private Long taskInputMessageId(AiTaskEntity task) {
        if (task.getTaskInputJson() == null || task.getTaskInputJson().isBlank()) {
            return null;
        }
        try {
            return new ObjectMapper()
                    .readValue(task.getTaskInputJson(), ConversationReplyTaskInputV1.class)
                    .messageId();
        } catch (JsonProcessingException exception) {
            return null;
        }
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
        String targetStatus = TASK_TYPE_CONVERSATION_REPLY.equals(task.getTaskType())
                && STATUS_RUNNING.equals(task.getTaskStatus())
                ? STATUS_CANCELING
                : STATUS_CANCELED;
        UpdateWrapper<AiTaskEntity> update = new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", currentVersion)
                .eq("task_status", task.getTaskStatus())
                .in("task_status", CANCELABLE_STATUSES)
                .set("task_status", targetStatus)
                .set("version", currentVersion + 1)
                .set("gmt_modified", modifiedAt);
        if (taskMapper.update(null, update) == 1) {
            return new AiTaskCanceled(task.getId(), targetStatus, modifiedAt);
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
                task.getResultOutlineCandidateId(),
                agentRunId(task.getId()),
                task.getRetryOfTaskId(),
                effectiveReplyPolicy(task),
                task.getErrorCode(),
                task.getErrorCode() == null
                        ? null
                        : PublicFailureFactory.safeMessage(task.getErrorCode(), task.getErrorMessage()),
                task.getGmtCreate(),
                task.getGmtModified(),
                task.getErrorCode() == null ? null
                        : PublicFailureFactory.from(task.getErrorCode(), task.getDiagnosticRef()));
    }

    private EffectiveReplyPolicy effectiveReplyPolicy(AiTaskEntity task) {
        if (task == null || !TASK_TYPE_CONVERSATION_REPLY.equals(task.getTaskType())
                || task.getTaskInputJson() == null || task.getTaskInputJson().isBlank()) {
            return null;
        }
        try {
            ConversationReplyTaskInputV1 input =
                    new ObjectMapper().readValue(task.getTaskInputJson(), ConversationReplyTaskInputV1.class);
            return new EffectiveReplyPolicy(
                    input.replyMode().value(),
                    input.replyDepth().value(),
                    input.replyScope(),
                    input.controlSource(),
                    input.policyVersion(),
                    input.convergenceApplied());
        } catch (JsonProcessingException | NullPointerException exception) {
            return null;
        }
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

    private void publishCancellationState(AiTaskEntity task, String targetStatus) {
        if (TASK_TYPE_CONVERSATION_REPLY.equals(task.getTaskType())) {
            eventPublisher.publishEvent(STATUS_CANCELING.equals(targetStatus)
                    ? ChapterReplyEvent.canceling(task.getChapterId(), task.getId())
                    : ChapterReplyEvent.canceled(task.getChapterId(), task.getId(), null));
        }
        if (TASK_TYPE_OUTLINE_CANDIDATE.equals(task.getTaskType()) && candidateMapper != null) {
            ChapterOutlineCandidateEntity candidate = candidateMapper.findByTaskId(task.getId());
            if (candidate != null) {
                eventPublisher.publishEvent(OutlineCandidateEvent.updated(
                        task.getChapterId(), task.getId(), candidate.getId(), STATUS_CANCELED, STATUS_CANCELED,
                        candidate.getBaseOutlineId(), candidate.getBaseOutlineRevision()));
            }
        }
    }

    private Long agentRunId(Long taskId) {
        if (agentRunMapper == null) {
            return null;
        }
        AgentRunEntity run = agentRunMapper.findByAiTaskId(taskId);
        return run == null ? null : run.getId();
    }

    /**
     * 取消候选任务时同步将同一事务内的候选资源标记为 canceled。
     *
     * @param task 已成功取消的任务
     */
    private void markCandidateCanceled(AiTaskEntity task) {
        if (!TASK_TYPE_OUTLINE_CANDIDATE.equals(task.getTaskType()) || candidateMapper == null) {
            return;
        }
        ChapterOutlineCandidateEntity candidate = candidateMapper.findByTaskId(task.getId());
        if (candidate == null) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_NOT_FOUND, "候选任务关联资源不存在");
        }
        int version = candidate.getVersion() == null ? 0 : candidate.getVersion();
        int changed = candidateMapper.update(null, new UpdateWrapper<ChapterOutlineCandidateEntity>()
                .eq("id", candidate.getId())
                .eq("ai_task_id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .in("candidate_status", Set.of("queued", STATUS_RUNNING))
                .set("candidate_status", STATUS_CANCELED)
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (changed != 1) {
            throw new BusinessException(ErrorCode.AI_TASK_STATE_CONFLICT, "候选任务状态已变化，请重试");
        }
    }

    private void publishProviderCancellation(AiTaskEntity task) {
        if (STATUS_RUNNING.equals(task.getTaskStatus())) {
            eventPublisher.publishEvent(new AiTaskCancellationSignal(task.getId()));
        }
    }
}
