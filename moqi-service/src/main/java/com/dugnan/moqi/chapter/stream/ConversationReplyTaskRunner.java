package com.dugnan.moqi.chapter.stream;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.focus.ChapterDiscussionFocusResolver;
import com.dugnan.moqi.chapter.focus.ResolvedDiscussionFocus;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextBuildCommand;
import com.dugnan.moqi.context.StoryContextProfile;
import com.dugnan.moqi.context.StoryContextFocus;
import com.dugnan.moqi.context.StoryContextFocus.StoryContextFocusSource;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextTaskBindingException;
import com.dugnan.moqi.context.StoryContextTaskBindingService;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmRole;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamCallRegistry;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 执行章节讨论回复任务，并将模型增量转换为应用事件。
 */
@Component
public class ConversationReplyTaskRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationReplyTaskRunner.class);

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
    private final LlmStreamCallRegistry callRegistry;
    private final StoryContextTaskBindingService contextBindingService;
    private final ChapterDiscussionFocusResolver focusResolver;

    /**
     * 创建完整接入上下文引擎与讨论对焦的回复任务执行器。
     *
     * @param taskMapper AI 任务数据访问对象
     * @param messageMapper 会话消息数据访问对象
     * @param userConfigService 用户配置服务
     * @param providerFactory LLM Provider 工厂
     * @param persistenceService 回复持久化服务
     * @param eventPublisher 应用事件发布器
     * @param callRegistry 流式调用注册表
     * @param contextBindingService 故事上下文绑定服务
     * @param focusResolver 讨论对焦解析器
     */
    @Autowired
    public ConversationReplyTaskRunner(
            AiTaskMapper taskMapper,
            ChapterConversationMessageMapper messageMapper,
            UserConfigService userConfigService,
            LlmProviderFactory providerFactory,
            ConversationReplyPersistenceService persistenceService,
            ApplicationEventPublisher eventPublisher,
            LlmStreamCallRegistry callRegistry,
            StoryContextTaskBindingService contextBindingService,
            ChapterDiscussionFocusResolver focusResolver) {
        this.taskMapper = taskMapper;
        this.messageMapper = messageMapper;
        this.userConfigService = userConfigService;
        this.providerFactory = providerFactory;
        this.persistenceService = persistenceService;
        this.eventPublisher = eventPublisher;
        this.callRegistry = callRegistry;
        this.contextBindingService = contextBindingService;
        this.focusResolver = focusResolver;
    }

    /**
     * 保留无上下文引擎的构造入口，供既有单元测试和轻量调用方使用。
     *
     * @param taskMapper AI 任务数据访问对象
     * @param messageMapper 会话消息数据访问对象
     * @param userConfigService 用户配置服务
     * @param providerFactory LLM Provider 工厂
     * @param persistenceService 回复持久化服务
     * @param eventPublisher 应用事件发布器
     * @param callRegistry 流式调用注册表
     */
    public ConversationReplyTaskRunner(
            AiTaskMapper taskMapper,
            ChapterConversationMessageMapper messageMapper,
            UserConfigService userConfigService,
            LlmProviderFactory providerFactory,
            ConversationReplyPersistenceService persistenceService,
            ApplicationEventPublisher eventPublisher,
            LlmStreamCallRegistry callRegistry) {
        this(taskMapper, messageMapper, userConfigService, providerFactory, persistenceService,
                eventPublisher, callRegistry, null, null);
    }

    /**
     * 保留只接入 Story Context Engine、不含讨论对焦解析器的构造入口。
     *
     * @param taskMapper AI 任务数据访问对象
     * @param messageMapper 会话消息数据访问对象
     * @param userConfigService 用户配置服务
     * @param providerFactory LLM Provider 工厂
     * @param persistenceService 回复持久化服务
     * @param eventPublisher 应用事件发布器
     * @param callRegistry 流式调用注册表
     * @param contextBindingService 故事上下文绑定服务
     */
    public ConversationReplyTaskRunner(
            AiTaskMapper taskMapper,
            ChapterConversationMessageMapper messageMapper,
            UserConfigService userConfigService,
            LlmProviderFactory providerFactory,
            ConversationReplyPersistenceService persistenceService,
            ApplicationEventPublisher eventPublisher,
            LlmStreamCallRegistry callRegistry,
            StoryContextTaskBindingService contextBindingService) {
        this(
                taskMapper,
                messageMapper,
                userConfigService,
                providerFactory,
                persistenceService,
                eventPublisher,
                callRegistry,
                contextBindingService,
                null);
    }

    /**
     * 执行一个 queued 讨论回复任务并发布流式事件。
     *
     * @param taskId 任务 ID
     */
    public void run(Long taskId) {
        AiTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null || Integer.valueOf(1).equals(task.getDeleted())
                || !TASK_TYPE.equals(task.getTaskType()) || !claim(task)) {
            return;
        }
        LlmStreamCall call = null;
        try {
            ChapterConversationMessageEntity input = requireInputMessage(task.getId());
            LlmProvider provider = providerFactory.create(userConfigService.requireAvailableModelConfig());
            StringBuilder response = new StringBuilder();
            StoryContextSnapshot contextSnapshot = buildContext(task, input, provider);
            eventPublisher.publishEvent(ChapterReplyEvent.started(task.getChapterId(), task.getId()));
            call = provider.stream(
                    contextSnapshot == null ? request(input) : request(contextSnapshot),
                    event -> {
                        if (event instanceof LlmStreamEvent.TextDelta delta
                                && !callRegistry.isCancellationRequested(task.getId())
                                && StringUtils.hasText(delta.text())) {
                            response.append(delta.text());
                            eventPublisher.publishEvent(ChapterReplyEvent.delta(
                                    task.getChapterId(), task.getId(), delta.text()));
                        }
                    });
            callRegistry.register(task.getId(), call);
            LlmStreamResult streamResult = call.await();
            if (streamResult.status() == LlmStreamStatus.CANCELED) {
                throw new ConversationReplyTaskCanceledException();
            }
            if (streamResult.status() == LlmStreamStatus.FAILED) {
                throw new LlmProviderException(streamResult.error());
            }
            ensureRunning(task);
            Long messageId = persistenceService.complete(task, input, response.toString());
            eventPublisher.publishEvent(ChapterReplyEvent.completed(task.getChapterId(), task.getId(), messageId));
        } catch (ConversationReplyTaskCanceledException exception) {
            // 取消事件由取消服务发布，执行器不覆盖已取消状态。
        } catch (StoryContextTaskBindingException exception) {
            // 快照关联竞争失败时保持任务终态，不调用模型。
        } catch (LlmProviderException exception) {
            fail(task, exception.getError().name(), exception.getMessage());
        } catch (BusinessException exception) {
            fail(task, exception.getErrorCode().name(), exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "章节讨论回复任务发生未预期异常，taskId={}, chapterId={}, exceptionType={}",
                    task.getId(),
                    task.getChapterId(),
                    exception.getClass().getName(),
                    exception);
            fail(task, "INTERNAL_ERROR", "AI 回复生成失败，请稍后重试");
        } finally {
            callRegistry.unregister(task.getId(), call);
        }
    }

    /**
     * 将队列拒绝稳定写为失败并发布失败事件。
     *
     * @param taskId 任务 ID
     */
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
        if (!isRunning(task)) {
            throw new ConversationReplyTaskCanceledException();
        }
    }

    private boolean isRunning(AiTaskEntity task) {
        AiTaskEntity latest = taskMapper.selectById(task.getId());
        return latest != null && STATUS_RUNNING.equals(latest.getTaskStatus());
    }

    private StoryContextSnapshot buildContext(
            AiTaskEntity task,
            ChapterConversationMessageEntity input,
            LlmProvider provider) {
        if (contextBindingService == null) {
            return null;
        }
        int contextWindow = provider.capabilities().maxContextTokens() == null
                ? 32768 : provider.capabilities().maxContextTokens();
        int outputReserve = StoryContextProfile.CHAPTER_DISCUSSION.defaultOutputReserveTokens();
        if (provider.capabilities().maxOutputTokens() != null) {
            outputReserve = Math.min(outputReserve, provider.capabilities().maxOutputTokens());
        }
        return contextBindingService.buildAndAttach(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION,
                task.getWorkId(),
                task.getChapterId(),
                input.getConversationId(),
                input.getId(),
                "围绕用户当前问题给出清晰、可执行的章节共创建议。",
                input.getContent(),
                null,
                contextWindow,
                outputReserve,
                resolveFocus(task, input)), task);
    }

    /**
     * 根据消息持久化引用解析讨论对焦，客户端正文不参与组装。
     *
     * @param task 当前任务
     * @param input 当前用户消息
     * @return 故事上下文对焦资料
     */
    private StoryContextFocus resolveFocus(
            AiTaskEntity task,
            ChapterConversationMessageEntity input) {
        if (input.getFocusBriefId() == null && !StringUtils.hasText(input.getFocusDecisionKey())) {
            return null;
        }
        if (focusResolver == null) {
            throw new BusinessException(
                    com.dugnan.moqi.common.api.ErrorCode.DISCUSSION_FOCUS_INVALID,
                    "讨论对焦解析器不可用");
        }
        ResolvedDiscussionFocus resolved = focusResolver.resolve(
                task.getChapterId(),
                input.getConversationId(),
                input.getFocusBriefId(),
                input.getFocusDecisionKey());
        String decisionContent = "待决：" + resolved.decisionTitle()
                + "\n问题：" + resolved.decisionPrompt()
                + "\n当前候选：" + resolved.candidateSummary();
        List<StoryContextFocusSource> sources = resolved.sources().stream()
                .map(source -> new StoryContextFocusSource(
                        source.messageId(),
                        source.messageRole(),
                        source.content()))
                .toList();
        return new StoryContextFocus(
                resolved.briefId(),
                resolved.briefVersion(),
                resolved.decisionKey(),
                decisionContent,
                resolved.consensusContent(),
                sources);
    }

    private LlmRequest request(StoryContextSnapshot snapshot) {
        return new LlmRequest(
                snapshot.toMessages(),
                new LlmOptions(snapshot.outputReserveTokens(), null, List.of(), LlmResponseFormat.TEXT));
    }

    private LlmRequest request(ChapterConversationMessageEntity input) {
        return new LlmRequest(
                List.of(
                        new LlmMessage(
                                LlmRole.SYSTEM,
                                "你是墨契的章节共创助手，请围绕用户当前问题给出清晰、可执行的创作建议。"),
                        new LlmMessage(LlmRole.USER, input.getContent())),
                new LlmOptions(null, null, List.of(), LlmResponseFormat.TEXT));
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
