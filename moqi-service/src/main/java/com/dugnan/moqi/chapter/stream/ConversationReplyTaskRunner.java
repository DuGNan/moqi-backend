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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.interaction.DiscussionInteractionCodec;
import com.dugnan.moqi.chapter.interaction.DiscussionInteractionCodec.AssistantResult;
import com.dugnan.moqi.chapter.focus.ChapterDiscussionFocusResolver;
import com.dugnan.moqi.chapter.focus.ResolvedDiscussionFocus;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.policy.ConversationReplyTaskInputV1;
import com.dugnan.moqi.chapter.policy.DefaultReplyPolicyResolver;
import com.dugnan.moqi.chapter.policy.ReplyDepth;
import com.dugnan.moqi.chapter.policy.ReplyMode;
import com.dugnan.moqi.chapter.policy.ResolvedReplyPolicy;
import com.dugnan.moqi.common.exception.BusinessException;
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

    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32768;
    private static final int MIN_CONTEXT_WINDOW_TOKENS = 2;
    private static final int MIN_INPUT_RESERVE_TOKENS = 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationReplyTaskRunner.class);

    private static final String TASK_TYPE = "conversation_reply";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_CANCELING = "canceling";
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
        executeClaimedTask(task);
    }

    private void executeClaimedTask(AiTaskEntity task) {
        ProviderCallState state = new ProviderCallState();
        try {
            performProviderCall(task, state);
        } catch (ConversationReplyTaskCanceledException exception) {
            stopCanceledTask(task, state);
        } catch (StoryContextTaskBindingException exception) {
            // 快照关联竞争失败时保持任务终态，不调用模型。
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
        eventPublisher.publishEvent(ChapterReplyEvent.started(task.getChapterId(), task.getId()));
        boolean structured = isStructuredInteraction(taskInput.replyMode());
        state.publishPartial = !structured;
        state.call = provider.stream(
                contextSnapshot == null ? request(input, taskInput) : request(contextSnapshot, taskInput),
                event -> appendDelta(task, response, event, !structured));
        callRegistry.register(task.getId(), state.call);
        LlmStreamResult streamResult = state.call.await();
        ensureCompleted(streamResult);
        ensureRunning(task);
        AssistantResult result = structured
                ? DiscussionInteractionCodec.parseAssistantEnvelope(response.toString(), objectMapper())
                : new AssistantResult(response.toString(), null, null);
        if (structured && StringUtils.hasText(result.content())) {
            eventPublisher.publishEvent(ChapterReplyEvent.delta(task.getChapterId(), task.getId(), result.content()));
        }
        Long messageId = persistenceService.complete(task, input, result.content(), result.interactionJson());
        eventPublisher.publishEvent(ChapterReplyEvent.completed(task.getChapterId(), task.getId(), messageId));
    }

    private void appendDelta(
            AiTaskEntity task,
            StringBuilder response,
            LlmStreamEvent event,
            boolean publish) {
        if (event instanceof LlmStreamEvent.TextDelta delta
                && !callRegistry.isCancellationRequested(task.getId())
                && StringUtils.hasText(delta.text())) {
            response.append(delta.text());
            if (publish) {
                eventPublisher.publishEvent(ChapterReplyEvent.delta(
                        task.getChapterId(), task.getId(), delta.text()));
            }
        }
    }

    private void ensureCompleted(LlmStreamResult streamResult) {
        if (streamResult.status() == LlmStreamStatus.CANCELED) {
            throw new ConversationReplyTaskCanceledException();
        }
        if (streamResult.status() == LlmStreamStatus.FAILED) {
            throw new LlmProviderException(streamResult.error());
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
        int outputReserve = outputBudget(taskInput.replyMode(), taskInput.replyDepth());
        if (provider.capabilities().maxOutputTokens() != null) {
            outputReserve = Math.min(outputReserve, provider.capabilities().maxOutputTokens());
        }
        int inputReserve = Math.min(MIN_INPUT_RESERVE_TOKENS, contextWindow - 1);
        outputReserve = Math.min(outputReserve, contextWindow - inputReserve);
        return contextBindingService.buildAndAttach(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION,
                task.getWorkId(),
                task.getChapterId(),
                input.getConversationId(),
                input.getId(),
                taskRule(taskInput),
                input.getContent(),
                null,
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
                        outputBudget(taskInput.replyMode(), taskInput.replyDepth()),
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
        String modeRule = switch (input.replyMode()) {
            case EXPLORE -> "硬约束：先理解并综合作者刚表达的意图，再只沿最有价值的一条因果、人物动机、冲突或叙事后果线索展开成连贯讨论。用户没有要求选择时，严禁生成任何显式或隐式的多方案结构，包括‘候选方向’‘选项’‘方案 A/B’‘需要你决定’‘几条路径’‘几种质地’‘第一种/第二种/第三种’及其同义改写；不得用编号、项目符号或多个小标题陈列替代方向，不得列出让作者逐项作答的问题。只有确实能帮助作者继续形成自己想法时，结尾可以提出一个开放问题；整条回复最多出现一个问号，而且该问题不得内含‘还是’等并列选择。若无法同时满足，以不提问、继续综合拓展为准。";
            case CLARIFY -> "只提出一个最影响方向的澄清问题，不自行扩写完整方案。";
            case COMPARE -> "用户已明确要求候选或比较；给出差异明确的少量候选，也必须保留用户自行描述的空间。";
            case CONVERGE -> "只总结已确认内容和剩余待决，不新增大范围设定。";
            case PLAN -> "仅在请求范围内输出结构化规划，不生成正文。";
            case DRAFT -> "仅在请求范围内生成正文草稿，不把草稿自动确认为正式内容。";
        };
        String depthRule = switch (input.replyDepth()) {
            case BRIEF -> "保持简洁，优先约 150 至 400 字的体验基线。";
            case BALANCED -> "给出足够决策的信息，避免跨阶段扩写。";
            case DEEP -> "可以深入，但仍只处理本轮唯一主要意图。";
        };
        return "你是墨契的章节共创助手。"
                + modeRule
                + depthRule
                + "本轮主要意图：" + input.replyScope().primaryIntent()
                + "；允许修改：" + input.replyScope().allowedChanges()
                + "；最多候选数：" + input.replyScope().maxCandidates()
                + "。已确认内容才是权威事实；候选、待决、证据与已否定内容不得混用。"
                + (input.consecutiveQuestionSuppressed()
                        ? "上一轮已经提问或给出选项，本轮必须先综合用户回答并拓展思考，不得再次连续出题。" : "")
                + (input.crossChapterRequested()
                        ? "用户已明确要求跨章讨论，可以在请求范围内涉及后续章节。"
                        : "只讨论当前章节，不得主动设计、追问或预设下一章。")
                + "除非用户明确索要建议，不得输出‘我的倾向’或替作者做决定；不得用连续二选一代替共同思考。"
                + "不得宣称已经确认、保存或更新任何 Brief、大纲、场景规划或正文。"
                + (isStructuredInteraction(input.replyMode()) ? structuredInteractionRule(input.replyMode()) : "");
    }

    private String structuredInteractionRule(ReplyMode mode) {
        String interactionRule = mode == ReplyMode.COMPARE
                ? "interaction.type 必须为 single_choice，options 必须为 2 至 4 项，allowCustom 必须为 true。"
                : "interaction.type 必须为 open_question，options 必须为空数组，allowCustom 必须为 true。";
        return "只输出一个 JSON 对象，不要 Markdown 代码块。对象格式为："
                + "{\"content\":\"给作者看的简短引导\",\"interaction\":{\"schemaVersion\":1,"
                + "\"type\":\"single_choice 或 open_question\",\"questionId\":\"本问题稳定唯一 ID\","
                + "\"question\":\"问题\",\"options\":[{\"optionId\":\"唯一 ID\",\"title\":\"标题\","
                + "\"description\":\"说明\",\"tradeoffs\":\"取舍\"}],\"allowCustom\":true}}。"
                + interactionRule;
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

    private boolean stopCanceledTask(AiTaskEntity task, ProviderCallState state) {
        AiTaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null || !STATUS_CANCELING.equals(latest.getTaskStatus())) {
            return false;
        }
        Long messageId = persistenceService.stop(
                latest,
                state.input,
                state.publishPartial ? state.response.toString() : "");
        eventPublisher.publishEvent(ChapterReplyEvent.canceled(
                latest.getChapterId(), latest.getId(), messageId));
        return true;
    }

    private int version(AiTaskEntity task) {
        return task.getVersion() == null ? 0 : task.getVersion();
    }

    private static final class ProviderCallState {
        private LlmStreamCall call;
        private ChapterConversationMessageEntity input;
        private final StringBuilder response = new StringBuilder();
        private boolean publishPartial;
    }

}
