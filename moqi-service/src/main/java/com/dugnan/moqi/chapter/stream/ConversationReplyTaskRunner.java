package com.dugnan.moqi.chapter.stream;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 执行章节讨论回复任务，并将模型增量转换为应用事件。
 */
@Component
public class ConversationReplyTaskRunner {

    private static final String TASK_TYPE = "conversation_reply";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";

    private final AiTaskMapper taskMapper;
    private final ChapterConversationMessageMapper messageMapper;
    private final UserConfigService userConfigService;
    private final LlmProviderFactory providerFactory;
    private final ConversationReplyPersistenceService persistenceService;
    private final ApplicationEventPublisher eventPublisher;

    public ConversationReplyTaskRunner(
            AiTaskMapper taskMapper,
            ChapterConversationMessageMapper messageMapper,
            UserConfigService userConfigService,
            LlmProviderFactory providerFactory,
            ConversationReplyPersistenceService persistenceService,
            ApplicationEventPublisher eventPublisher) {
        this.taskMapper = taskMapper;
        this.messageMapper = messageMapper;
        this.userConfigService = userConfigService;
        this.providerFactory = providerFactory;
        this.persistenceService = persistenceService;
        this.eventPublisher = eventPublisher;
    }

    public void run(Long taskId) {
        AiTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null || Integer.valueOf(1).equals(task.getDeleted())
                || !TASK_TYPE.equals(task.getTaskType()) || !claim(task)) {
            return;
        }
        try {
            ChapterConversationMessageEntity input = requireInputMessage(task.getId());
            LlmProvider provider = providerFactory.create(userConfigService.requireAvailableDeepSeekConfig());
            StringBuilder response = new StringBuilder();
            eventPublisher.publishEvent(ChapterReplyEvent.started(task.getChapterId(), task.getId()));
            provider.stream(
                    new LlmRequest("你是墨契的章节共创助手，请围绕用户当前问题给出清晰、可执行的创作建议。", input.getContent(), null),
                    delta -> {
                        ensureRunning(task);
                        if (StringUtils.hasText(delta.text())) {
                            response.append(delta.text());
                            eventPublisher.publishEvent(ChapterReplyEvent.delta(
                                    task.getChapterId(), task.getId(), delta.text()));
                        }
                    });
            ensureRunning(task);
            Long messageId = persistenceService.complete(task, input, response.toString());
            eventPublisher.publishEvent(ChapterReplyEvent.completed(task.getChapterId(), task.getId(), messageId));
        } catch (ConversationReplyTaskCanceledException exception) {
            // 取消事件由取消服务发布，执行器不覆盖已取消状态。
        } catch (LlmProviderException exception) {
            fail(task, exception.getError().name(), exception.getMessage());
        } catch (BusinessException exception) {
            fail(task, exception.getErrorCode().name(), exception.getMessage());
        } catch (RuntimeException exception) {
            fail(task, "INTERNAL_ERROR", "AI 回复生成失败，请稍后重试");
        }
    }

    public void reject(Long taskId) {
        AiTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null || !STATUS_QUEUED.equals(task.getTaskStatus())) {
            return;
        }
        int version = version(task);
        if (taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .eq("task_status", STATUS_QUEUED)
                .set("task_status", STATUS_FAILED)
                .set("error_code", "TASK_QUEUE_FULL")
                .set("error_message", "AI 回复任务繁忙，请稍后重试")
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now())) == 1) {
            eventPublisher.publishEvent(ChapterReplyEvent.failed(
                    task.getChapterId(), task.getId(), "TASK_QUEUE_FULL", "AI 回复任务繁忙，请稍后重试"));
        }
    }

    private boolean claim(AiTaskEntity task) {
        int version = version(task);
        LocalDateTime modifiedAt = LocalDateTime.now();
        int updated = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .eq("task_status", STATUS_QUEUED)
                .set("task_status", STATUS_RUNNING)
                .set("version", version + 1)
                .set("gmt_modified", modifiedAt));
        if (updated == 1) {
            task.setTaskStatus(STATUS_RUNNING);
            task.setVersion(version + 1);
            task.setGmtModified(modifiedAt);
            return true;
        }
        return false;
    }

    private ChapterConversationMessageEntity requireInputMessage(Long taskId) {
        List<ChapterConversationMessageEntity> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ChapterConversationMessageEntity>()
                        .eq(ChapterConversationMessageEntity::getAiTaskId, taskId)
                        .eq(ChapterConversationMessageEntity::getMessageRole, "user")
                        .eq(ChapterConversationMessageEntity::getDeleted, 0)
                        .orderByDesc(ChapterConversationMessageEntity::getId));
        if (messages.isEmpty()) {
            throw new IllegalStateException("conversation_reply 任务缺少用户输入消息");
        }
        return messages.get(0);
    }

    private void ensureRunning(AiTaskEntity task) {
        AiTaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null || !STATUS_RUNNING.equals(latest.getTaskStatus())) {
            throw new ConversationReplyTaskCanceledException();
        }
    }

    private void fail(AiTaskEntity task, String errorCode, String errorMessage) {
        if (task == null) {
            return;
        }
        int version = version(task);
        int updated = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .eq("task_status", STATUS_RUNNING)
                .set("task_status", STATUS_FAILED)
                .set("error_code", errorCode)
                .set("error_message", errorMessage)
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (updated == 1) {
            eventPublisher.publishEvent(ChapterReplyEvent.failed(
                    task.getChapterId(), task.getId(), errorCode, errorMessage));
        }
    }

    private int version(AiTaskEntity task) {
        return task.getVersion() == null ? 0 : task.getVersion();
    }

}
