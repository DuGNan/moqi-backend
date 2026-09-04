package com.dugnan.moqi.chapter.stream;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.interaction.DiscussionInteractionCodec;
import com.dugnan.moqi.chapter.interaction.DiscussionInteractionCodec.AssistantResult;
import com.dugnan.moqi.chapter.interaction.DiscussionInteractionCodec.StructuredOutputException;
import com.dugnan.moqi.chapter.interaction.ConversationReplyContentSanitizer;
import com.dugnan.moqi.chapter.focus.ChapterDiscussionFocusResolver;
import com.dugnan.moqi.chapter.focus.ResolvedDiscussionFocus;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.policy.ConversationReplyPromptCompiler;
import com.dugnan.moqi.chapter.policy.ConversationReplyTaskInputV1;
import com.dugnan.moqi.chapter.policy.DefaultReplyPolicyResolver;
import com.dugnan.moqi.chapter.policy.ReplyDepth;
import com.dugnan.moqi.chapter.policy.ReplyMode;
import com.dugnan.moqi.chapter.policy.ResolvedReplyPolicy;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.common.api.PublicFailureFactory;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextBuildCommand;
import com.dugnan.moqi.context.MessageReference;
import com.dugnan.moqi.context.StoryContextProfile;
import com.dugnan.moqi.context.StoryContextFocus;
import com.dugnan.moqi.context.StoryContextFocus.StoryContextFocusSource;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextTaskBindingException;
import com.dugnan.moqi.context.StoryContextTaskBindingService;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmCallContext;
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
import com.dugnan.moqi.llm.LlmExecutionConfig;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 执行章节讨论回复任务，并将模型增量转换为应用事件。
 */
@Component
public class ConversationReplyTaskRunner {

    private static final ConversationReplyPromptCompiler PROMPT_COMPILER =
            new ConversationReplyPromptCompiler();
    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32768;
    private static final int MIN_CONTEXT_WINDOW_TOKENS = 2;
    private static final int MIN_INPUT_RESERVE_TOKENS = 1024;
    private static final int SUMMARY_MAX_OUTPUT_TOKENS = 4096;
    private static final int SUMMARY_BASE_OUTPUT_TOKENS = 1536;
    private static final int SUMMARY_TOKENS_PER_MESSAGE = 64;

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationReplyTaskRunner.class);

    private static final String TASK_TYPE = "conversation_reply";
    private static final String FACT_CORRECTION = "fact_correction";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_CANCELING = "canceling";
    private static final String FINISH_REASON_LENGTH = "length";
    private static final String FINISH_REASON_MAX_TOKENS = "max_tokens";
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
    private final ObjectMapper objectMapper;

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
     * @param objectMapper JSON 映射器
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
            ChapterDiscussionFocusResolver focusResolver,
            ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.messageMapper = messageMapper;
        this.userConfigService = userConfigService;
        this.providerFactory = providerFactory;
        this.persistenceService = persistenceService;
        this.eventPublisher = eventPublisher;
        this.callRegistry = callRegistry;
        this.contextBindingService = contextBindingService;
        this.focusResolver = focusResolver;
        this.objectMapper = objectMapper;
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
                eventPublisher, callRegistry, null, null, null);
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
                null,
                null);
    }

    /**
     * 保留同时接入上下文引擎和讨论对焦解析器的兼容构造入口。
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
        this(taskMapper, messageMapper, userConfigService, providerFactory, persistenceService,
                eventPublisher, callRegistry, contextBindingService, focusResolver, null);
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
        String diagnosticRef = PublicFailureFactory.newDiagnosticRef();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("diagnosticRef", diagnosticRef)) {
            executeClaimedTask(task);
        }
    }

    private void executeClaimedTask(AiTaskEntity task) {
        ProviderCallState state = new ProviderCallState();
        try {
            performProviderCall(task, state);
        } catch (ConversationReplyTaskCanceledException exception) {
            stopCanceledTask(task, state);
        } catch (StoryContextTaskBindingException exception) {
            // 快照关联竞争失败时保持任务终态，不调用模型。
        } catch (StructuredOutputException exception) {
            LOGGER.warn(
                    "章节讨论结构化回复无法安全恢复，taskId={}, chapterId={}, reason={}",
                    task.getId(), task.getChapterId(), exception.getMessage());
            fail(task, "CONVERSATION_REPLY_JSON_INVALID", exception.getMessage());
        } catch (ConversationReplyTruncatedException exception) {
            fail(task, "CONVERSATION_REPLY_TRUNCATED", exception.getMessage());
        } catch (LlmProviderException exception) {
            if (!stopCanceledTask(task, state)) {
                fail(task, exception.getError().name(), exception.getMessage());
            }
        } catch (BusinessException exception) {
            if (!stopCanceledTask(task, state)) {
                fail(task, exception.getErrorCode().name(), exception.getMessage());
            }
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "章节讨论回复任务发生未预期异常，taskId={}, chapterId={}, exceptionType={}",
                    task.getId(),
                    task.getChapterId(),
                    exception.getClass().getName(),
                    exception);
            if (!stopCanceledTask(task, state)) {
                fail(task, "INTERNAL_ERROR", "AI 回复生成失败，请稍后重试");
            }
        } finally {
            callRegistry.unregister(task.getId(), state.call);
        }
    }

    private void performProviderCall(AiTaskEntity task, ProviderCallState state) {
        ChapterConversationMessageEntity input = requireInputMessage(task);
        state.input = input;
        ConversationReplyTaskInputV1 taskInput = readTaskInput(task, input);
        LlmExecutionConfig executionConfig = userConfigService.requireAvailableExecutionConfig();
        LlmProvider provider = providerFactory.create(executionConfig.runtimeConfig());
        StringBuilder response = state.response;
        StoryContextSnapshot contextSnapshot = buildContext(task, input, provider, taskInput);
        provider = providerFactory.createObserved(
                executionConfig,
                LlmCallContext.builder(TASK_TYPE, "generate_reply")
                        .workId(task.getWorkId())
                        .chapterId(task.getChapterId())
                        .aiTaskId(task.getId())
                        .conversationId(taskInput.conversationId())
                        .logicalCallId("ai-task:" + task.getId() + ":reply")
                        .promptTemplateVersion(taskInput.policyVersion())
                        .sourceFingerprint(contextSnapshot == null ? "legacy" : contextSnapshot.contentHash())
                        .replyPolicy(
                                taskInput.replyMode().name().toLowerCase(Locale.ROOT),
                                taskInput.replyDepth().name().toLowerCase(Locale.ROOT),
                                scopeSummary(taskInput),
                                taskInput.controlSource(),
                                taskInput.policyVersion())
                        .build());
        eventPublisher.publishEvent(ChapterReplyEvent.started(
                task.getChapterId(), task.getId(), input.getConversationId()));
        boolean structured = isStructuredInteraction(taskInput.replyMode());
        boolean delayedOutput = structured || isCurrentFactCorrection(taskInput);
        state.publishPartial = !delayedOutput;
        state.call = provider.stream(
                contextSnapshot == null ? request(input, taskInput) : request(contextSnapshot, taskInput),
                event -> appendDelta(task, input.getConversationId(), state, event, !delayedOutput));
        callRegistry.register(task.getId(), state.call);
        LlmStreamResult streamResult = state.call.await();
        ensureCompleted(streamResult);
        ensureNotTruncated(taskInput, streamResult);
        ensureRunning(task);
        AssistantResult result = structured
                ? parseStructuredResult(task, taskInput, response.toString())
                : new AssistantResult(response.toString(), null, null);
        result = new AssistantResult(
                ConversationReplyContentSanitizer.stripLeadingCandidateNotices(result.content()),
                result.interaction(),
                result.interactionJson(),
                result.degradationReason());
        result = normalizeFactCorrection(taskInput, result);
        if (result.degradationReason() != null) {
            LOGGER.warn(
                    "章节讨论结构化回复已降级为普通文本，taskId={}, chapterId={}, reason={}",
                    task.getId(), task.getChapterId(), result.degradationReason());
        }
        if (delayedOutput && StringUtils.hasText(result.content())) {
            eventPublisher.publishEvent(ChapterReplyEvent.delta(
                    task.getChapterId(), task.getId(), input.getConversationId(), result.content()));
        }
        Long messageId = persistenceService.complete(task, input, result.content(), result.interactionJson());
        eventPublisher.publishEvent(ChapterReplyEvent.completed(
                task.getChapterId(), task.getId(), input.getConversationId(), messageId));
    }

    private void appendDelta(
            AiTaskEntity task,
            Long conversationId,
            ProviderCallState state,
            LlmStreamEvent event,
            boolean publish) {
        if (event instanceof LlmStreamEvent.TextDelta delta
                && !callRegistry.isCancellationRequested(task.getId())
                && StringUtils.hasText(delta.text())) {
            state.response.append(delta.text());
            if (publish) {
                publishVisibleDelta(task, conversationId, state);
            }
        }
    }

    private void publishVisibleDelta(AiTaskEntity task, Long conversationId, ProviderCallState state) {
        String visibleContent = ConversationReplyContentSanitizer.visibleStreamingContent(state.response.toString());
        if (visibleContent.length() <= state.publishedContentLength) {
            return;
        }
        String delta = visibleContent.substring(state.publishedContentLength);
        state.publishedContentLength = visibleContent.length();
        eventPublisher.publishEvent(ChapterReplyEvent.delta(
                task.getChapterId(), task.getId(), conversationId, delta));
    }

    private void ensureCompleted(LlmStreamResult streamResult) {
        if (streamResult.status() == LlmStreamStatus.CANCELED) {
            throw new ConversationReplyTaskCanceledException();
        }
        if (streamResult.status() == LlmStreamStatus.FAILED) {
            throw new LlmProviderException(streamResult.error());
        }
    }

    private void ensureNotTruncated(
            ConversationReplyTaskInputV1 input,
            LlmStreamResult streamResult) {
        if (input.replyMode() != ReplyMode.CONVERGE || streamResult.metadata() == null) {
            return;
        }
        String finishReason = streamResult.metadata().finishReason();
        if (FINISH_REASON_LENGTH.equalsIgnoreCase(finishReason)
                || FINISH_REASON_MAX_TOKENS.equalsIgnoreCase(finishReason)) {
            throw new ConversationReplyTruncatedException("会话总结达到输出上限，未保存不完整结果");
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
        String diagnosticRef = StringUtils.hasText(task.getDiagnosticRef())
                ? task.getDiagnosticRef()
                : currentOrNewDiagnosticRef();
        if (taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .eq("task_status", STATUS_QUEUED)
                .set("task_status", STATUS_FAILED)
                .set("error_code", "TASK_QUEUE_FULL")
                .set("error_message", "AI 回复任务繁忙，请稍后重试")
                .set("diagnostic_ref", diagnosticRef)
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now())) == 1) {
            eventPublisher.publishEvent(ChapterReplyEvent.failed(
                    task.getChapterId(), task.getId(), "TASK_QUEUE_FULL", "AI 回复任务繁忙，请稍后重试",
                    diagnosticRef));
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

    private ChapterConversationMessageEntity requireInputMessage(AiTaskEntity task) {
        Long snapshotMessageId = taskInputMessageId(task);
        if (snapshotMessageId != null) {
            ChapterConversationMessageEntity snapshotMessage = messageMapper.selectById(snapshotMessageId);
            if (snapshotMessage == null
                    || Integer.valueOf(1).equals(snapshotMessage.getDeleted())
                    || !"user".equals(snapshotMessage.getMessageRole())) {
                throw new IllegalStateException("conversation_reply 任务快照引用的用户消息不存在");
            }
            return snapshotMessage;
        }
        List<ChapterConversationMessageEntity> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ChapterConversationMessageEntity>()
                        .eq(ChapterConversationMessageEntity::getAiTaskId, task.getId())
                        .eq(ChapterConversationMessageEntity::getMessageRole, "user")
                        .eq(ChapterConversationMessageEntity::getDeleted, 0)
                        .orderByDesc(ChapterConversationMessageEntity::getId));
        if (messages.isEmpty()) {
            throw new IllegalStateException("conversation_reply 任务缺少用户输入消息");
        }
        return messages.get(0);
    }

    private Long taskInputMessageId(AiTaskEntity task) {
        if (!StringUtils.hasText(task.getTaskInputJson())) {
            return null;
        }
        try {
            return objectMapper().readValue(task.getTaskInputJson(), ConversationReplyTaskInputV1.class).messageId();
        } catch (JsonProcessingException exception) {
            return null;
        }
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
            LlmProvider provider,
            ConversationReplyTaskInputV1 taskInput) {
        if (contextBindingService == null) {
            return null;
        }
        int contextWindow = provider.capabilities().maxContextTokens() == null
                ? DEFAULT_CONTEXT_WINDOW_TOKENS : provider.capabilities().maxContextTokens();
        if (contextWindow < MIN_CONTEXT_WINDOW_TOKENS) {
            throw new IllegalStateException("模型上下文窗口至少需要 2 tokens");
        }
        int outputReserve = outputBudget(taskInput, input);
        if (provider.capabilities().maxOutputTokens() != null) {
            outputReserve = Math.min(outputReserve, provider.capabilities().maxOutputTokens());
        }
        int inputReserve = Math.min(MIN_INPUT_RESERVE_TOKENS, contextWindow - 1);
        outputReserve = Math.min(outputReserve, contextWindow - inputReserve);
        StoryContextProfile profile = StringUtils.hasText(taskInput.proseObjectId())
                ? StoryContextProfile.PROSE_DISCUSSION : StoryContextProfile.CHAPTER_DISCUSSION;
        return contextBindingService.buildAndAttach(new StoryContextBuildCommand(
                profile,
                task.getWorkId(),
                task.getChapterId(),
                input.getConversationId(),
                input.getId(),
                taskRule(taskInput),
                input.getContent(),
                taskInput.proseTargetText(),
                contextWindow,
                outputReserve,
                resolveFocus(task, input), null, resolveMessageReference(input)), task);
    }

    private MessageReference resolveMessageReference(ChapterConversationMessageEntity input) {
        if (input.getReferencedMessageId() == null) {
            return null;
        }
        ChapterConversationMessageEntity referenced = messageMapper.selectById(input.getReferencedMessageId());
        if (!isAvailableMessageReference(input, referenced)) {
            throw new BusinessException(com.dugnan.moqi.common.api.ErrorCode.MESSAGE_REFERENCE_INVALID,
                    "引用消息不可用");
        }
        String content = "stopped".equals(referenced.getGenerationStatus())
                ? "[不完整：作者已停止本次生成，不得据此确认共识或写入权威内容]\n" + referenced.getContent()
                : referenced.getContent();
        return new MessageReference(referenced.getId(), referenced.getMessageRole(), content);
    }

    private boolean isAvailableMessageReference(
            ChapterConversationMessageEntity input,
            ChapterConversationMessageEntity referenced) {
        if (referenced == null || Integer.valueOf(1).equals(referenced.getDeleted())) {
            return false;
        }
        if (!input.getConversationId().equals(referenced.getConversationId())
                || !input.getChapterId().equals(referenced.getChapterId())) {
            return false;
        }
        return "user".equals(referenced.getMessageRole()) || "assistant".equals(referenced.getMessageRole());
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
        boolean isRejected = "rejected".equals(resolved.decisionStatus());
        String decisionContent = isRejected
                ? "已否定决定：" + resolved.decisionTitle() + "（" + resolved.decisionKey() + "），不得继承其内容。"
                : "待决：" + resolved.decisionTitle()
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
                resolved.decisionStatus(),
                decisionContent,
                resolved.consensusContent(),
                sources);
    }

    private LlmRequest request(StoryContextSnapshot snapshot, ConversationReplyTaskInputV1 taskInput) {
        return new LlmRequest(
                snapshot.toMessages(),
                new LlmOptions(snapshot.outputReserveTokens(), null, List.of(), responseFormat(taskInput.replyMode())));
    }

    private LlmRequest request(ChapterConversationMessageEntity input) {
        return request(input, legacyTaskInput(input));
    }

    private LlmRequest request(
            ChapterConversationMessageEntity input,
            ConversationReplyTaskInputV1 taskInput) {
        return new LlmRequest(
                List.of(
                        new LlmMessage(
                                LlmRole.SYSTEM,
                                taskRule(taskInput)),
                        new LlmMessage(LlmRole.USER, input.getContent())),
                new LlmOptions(
                        outputBudget(taskInput, input),
                        null,
                        List.of(),
                        responseFormat(taskInput.replyMode())));
    }

    private LlmResponseFormat responseFormat(ReplyMode mode) {
        return isStructuredInteraction(mode) ? LlmResponseFormat.JSON_OBJECT : LlmResponseFormat.TEXT;
    }

    private boolean isStructuredInteraction(ReplyMode mode) {
        return mode == ReplyMode.COMPARE || mode == ReplyMode.CLARIFY;
    }

    private AssistantResult normalizeFactCorrection(
            ConversationReplyTaskInputV1 input,
            AssistantResult result) {
        if (!isCurrentFactCorrection(input)
                || !StringUtils.hasText(input.replyScope().targetReference())) {
            return result;
        }
        return new AssistantResult(
                input.replyScope().targetReference().trim(),
                null,
                null,
                result.degradationReason());
    }

    private boolean isCurrentFactCorrection(ConversationReplyTaskInputV1 input) {
        return DefaultReplyPolicyResolver.POLICY_VERSION.equals(input.policyVersion())
                && FACT_CORRECTION.equals(input.replyScope().allowedChanges());
    }

    private AssistantResult parseStructuredResult(
            AiTaskEntity task,
            ConversationReplyTaskInputV1 input,
            String response) {
        if (!DefaultReplyPolicyResolver.POLICY_VERSION.equals(input.policyVersion())) {
            return DiscussionInteractionCodec.parseAssistantEnvelope(response, objectMapper());
        }
        String questionId = "task-" + task.getId();
        if (input.replyMode() == ReplyMode.COMPARE) {
            return DiscussionInteractionCodec.parseComparisonDraft(
                    response, objectMapper(), questionId);
        }
        return DiscussionInteractionCodec.parseClarificationDraft(response, objectMapper(), questionId);
    }

    private ObjectMapper objectMapper() {
        return objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    private ConversationReplyTaskInputV1 readTaskInput(
            AiTaskEntity task,
            ChapterConversationMessageEntity input) {
        if (!StringUtils.hasText(task.getTaskInputJson())) {
            return legacyTaskInput(input);
        }
        try {
            ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
            ConversationReplyTaskInputV1 snapshot =
                    mapper.readValue(task.getTaskInputJson(), ConversationReplyTaskInputV1.class);
            if (snapshot.schemaVersion() < 1
                    || snapshot.schemaVersion() > ConversationReplyTaskInputV1.SCHEMA_VERSION
                    || !input.getId().equals(snapshot.messageId())
                    || !input.getConversationId().equals(snapshot.conversationId())
                    || snapshot.replyMode() == null
                    || snapshot.replyDepth() == null
                    || snapshot.replyScope() == null) {
                throw new IllegalStateException("conversation_reply 策略快照与输入消息不一致");
            }
            return snapshot;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("conversation_reply 策略快照无法读取", exception);
        }
    }

    private ConversationReplyTaskInputV1 legacyTaskInput(ChapterConversationMessageEntity input) {
        ResolvedReplyPolicy policy = new DefaultReplyPolicyResolver()
                .resolve(input.getContent(), null, Map.of());
        return ConversationReplyTaskInputV1.from(input.getId(), input.getConversationId(), policy);
    }

    private String taskRule(ConversationReplyTaskInputV1 input) {
        return PROMPT_COMPILER.compile(input);
    }

    private int outputBudget(ReplyMode mode, ReplyDepth depth) {
        if (mode == ReplyMode.DRAFT) {
            return depth == ReplyDepth.BRIEF ? 1536 : depth == ReplyDepth.BALANCED ? 4096 : 8192;
        }
        if (mode == ReplyMode.PLAN) {
            return depth == ReplyDepth.BRIEF ? 1024 : depth == ReplyDepth.BALANCED ? 2048 : 4096;
        }
        return depth == ReplyDepth.BRIEF ? 768 : depth == ReplyDepth.BALANCED ? 1536 : 3072;
    }

    private int outputBudget(
            ConversationReplyTaskInputV1 taskInput,
            ChapterConversationMessageEntity input) {
        int baseBudget = outputBudget(taskInput.replyMode(), taskInput.replyDepth());
        if (taskInput.replyMode() != ReplyMode.CONVERGE
                || !DefaultReplyPolicyResolver.POLICY_VERSION.equals(taskInput.policyVersion())) {
            return baseBudget;
        }
        Long messageCount = messageMapper.selectCount(
                new LambdaQueryWrapper<ChapterConversationMessageEntity>()
                        .eq(ChapterConversationMessageEntity::getConversationId, input.getConversationId())
                        .eq(ChapterConversationMessageEntity::getDeleted, 0));
        long adaptiveBudget = SUMMARY_BASE_OUTPUT_TOKENS
                + (messageCount == null ? 0L : messageCount * SUMMARY_TOKENS_PER_MESSAGE);
        return (int) Math.min(
                SUMMARY_MAX_OUTPUT_TOKENS,
                Math.max(baseBudget, adaptiveBudget));
    }

    private String scopeSummary(ConversationReplyTaskInputV1 input) {
        return "intent=" + input.replyScope().primaryIntent()
                + ";target=" + input.replyScope().targetType()
                + ";changes=" + input.replyScope().allowedChanges()
                + ";maxCandidates=" + input.replyScope().maxCandidates();
    }

    private void fail(AiTaskEntity task, String errorCode, String errorMessage) {
        if (task == null) {
            return;
        }
        int version = version(task);
        String diagnosticRef = task.getDiagnosticRef() == null
                ? currentOrNewDiagnosticRef()
                : task.getDiagnosticRef();
        String publicErrorMessage = PublicFailureFactory.safeMessage(errorCode, errorMessage);
        int updated = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .eq("task_status", STATUS_RUNNING)
                .set("task_status", STATUS_FAILED)
                .set("error_code", errorCode)
                .set("error_message", publicErrorMessage)
                .set("diagnostic_ref", diagnosticRef)
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (updated == 1) {
            eventPublisher.publishEvent(ChapterReplyEvent.failed(
                    task.getChapterId(), task.getId(), errorCode, publicErrorMessage, diagnosticRef));
        }
    }

    private boolean stopCanceledTask(AiTaskEntity task, ProviderCallState state) {
        AiTaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null || !STATUS_CANCELING.equals(latest.getTaskStatus())) {
            return false;
        }
        Long messageId = persistenceService.stop(
                latest,
                state.input,
                state.publishPartial
                        ? ConversationReplyContentSanitizer.visibleStreamingContent(state.response.toString())
                        : "");
        eventPublisher.publishEvent(ChapterReplyEvent.canceled(
                latest.getChapterId(), latest.getId(), messageId));
        return true;
    }

    private String currentOrNewDiagnosticRef() {
        String diagnosticRef = MDC.get("diagnosticRef");
        return diagnosticRef == null || diagnosticRef.isBlank()
                ? PublicFailureFactory.newDiagnosticRef()
                : diagnosticRef;
    }

    private int version(AiTaskEntity task) {
        return task.getVersion() == null ? 0 : task.getVersion();
    }

    private static final class ProviderCallState {
        private LlmStreamCall call;
        private ChapterConversationMessageEntity input;
        private final StringBuilder response = new StringBuilder();
        private boolean publishPartial;
        private int publishedContentLength;
    }

    private static final class ConversationReplyTruncatedException extends RuntimeException {

        private ConversationReplyTruncatedException(String message) {
            super(message);
        }
    }

}
