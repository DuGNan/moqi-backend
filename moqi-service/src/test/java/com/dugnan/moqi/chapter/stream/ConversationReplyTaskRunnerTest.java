package com.dugnan.moqi.chapter.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextBuildCommand;
import com.dugnan.moqi.context.StoryContextItem;
import com.dugnan.moqi.context.StoryContextProfile;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextSourceType;
import com.dugnan.moqi.context.StoryContextTaskBindingService;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmCallContext;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmExecutionConfigDescriptor;
import com.dugnan.moqi.llm.LlmProviderError;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmProviderRuntimeConfig;
import com.dugnan.moqi.llm.LlmProviderCapabilities;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmResponseMetadata;
import com.dugnan.moqi.llm.LlmRole;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamCallRegistry;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 验证章节讨论任务使用 Provider V2 流事件并持久化成功回复。
 */
@ExtendWith(MockitoExtension.class)
class ConversationReplyTaskRunnerTest {

    @Mock
    private AiTaskMapper taskMapper;
    @Mock
    private ChapterConversationMessageMapper messageMapper;
    @Mock
    private UserConfigService userConfigService;
    @Mock
    private LlmProviderFactory providerFactory;
    @Mock
    private LlmProvider provider;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private StoryContextTaskBindingService contextBindingService;
    @BeforeEach
    void setUpObservedProvider() {
        lenient().when(userConfigService.requireAvailableExecutionConfig())
                .thenReturn(executionConfig("deepseek-v4-flash"));
        lenient().when(providerFactory.createObserved(any(), any())).thenReturn(provider);
        lenient().when(messageMapper.selectById(11L)).thenReturn(userMessage());
    }

    @Test
    void streamsReplyThenPersistsAssistantMessage() {
        AiTaskEntity task = task("queued", 0);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        lenient().when(userConfigService.requireAvailableModelConfig())
                .thenReturn(new LlmProviderRuntimeConfig(
                        "deepseek",
                        "https://api.deepseek.com",
                        "test-key",
                        "deepseek-v4-flash"));
        when(providerFactory.create(any())).thenReturn(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("第一段"));
            consumer.accept(new LlmStreamEvent.TextDelta("第二段"));
            return new CompletedCall();
        }).when(provider).stream(any(), any());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper,
                messageMapper,
                userConfigService,
                providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher,
                new LlmStreamCallRegistry()).run(12L);

        ArgumentCaptor<ChapterConversationMessageEntity> messageCaptor =
                ArgumentCaptor.forClass(ChapterConversationMessageEntity.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageRole()).isEqualTo("assistant");
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("第一段第二段");
        verify(eventPublisher).publishEvent(ChapterReplyEvent.completed(2L, 12L, 8L, 99L));
    }

    @Test
    void ignoresLateDeltaAndDoesNotPersistAfterCancellation() {
        AiTaskEntity task = task("queued", 0);
        AiTaskEntity canceling = task("canceling", 2);
        LlmStreamCallRegistry callRegistry = new LlmStreamCallRegistry();
        when(taskMapper.selectById(12L)).thenReturn(task, canceling, canceling);
        when(taskMapper.update(any(), any())).thenReturn(1);
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        lenient().when(userConfigService.requireAvailableModelConfig())
                .thenReturn(new LlmProviderRuntimeConfig(
                        "deepseek",
                        "https://api.deepseek.com",
                        "test-key",
                        "deepseek-v4-flash"));
        when(providerFactory.create(any())).thenReturn(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            callRegistry.cancel(12L);
            consumer.accept(new LlmStreamEvent.TextDelta("取消后的迟到增量"));
            return new CanceledCall();
        }).when(provider).stream(any(), any());

        new ConversationReplyTaskRunner(
                taskMapper,
                messageMapper,
                userConfigService,
                providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher,
                callRegistry).run(12L);

        verify(messageMapper, never()).insert(any(ChapterConversationMessageEntity.class));
        verify(eventPublisher, never()).publishEvent(
                ChapterReplyEvent.delta(2L, 12L, "取消后的迟到增量"));
        verify(eventPublisher).publishEvent(ChapterReplyEvent.canceled(2L, 12L, null));
        assertThat(callRegistry.isCancellationRequested(12L)).isFalse();
    }

    @Test
    void persistsVisiblePartialReplyBeforePublishingCanceled() {
        AiTaskEntity task = task("queued", 0);
        AiTaskEntity canceling = task("canceling", 2);
        when(taskMapper.selectById(12L)).thenReturn(task, canceling, canceling);
        when(taskMapper.update(any(), any())).thenReturn(1);
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        when(providerFactory.create(any())).thenReturn(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("已经展示的部分"));
            return new CanceledCall();
        }).when(provider).stream(any(), any());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(100L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper,
                messageMapper,
                userConfigService,
                providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher,
                new LlmStreamCallRegistry()).run(12L);

        ArgumentCaptor<ChapterConversationMessageEntity> messageCaptor =
                ArgumentCaptor.forClass(ChapterConversationMessageEntity.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("已经展示的部分");
        assertThat(messageCaptor.getValue().getGenerationStatus()).isEqualTo("stopped");
        verify(eventPublisher).publishEvent(ChapterReplyEvent.canceled(2L, 12L, 100L));
    }

    /**
     * 验证 Runner 只消费任务创建时的策略快照，并据此区分规划和正文预算。
     */
    @Test
    void consumesPersistedPolicySnapshot() {
        AiTaskEntity task = task("queued", 0);
        task.setTaskInputJson("""
                {
                  "schemaVersion":1,
                  "messageId":11,
                  "conversationId":8,
                  "replyMode":"plan",
                  "replyDepth":"deep",
                  "replyScope":{
                    "primaryIntent":"build_plan",
                    "targetType":"chapter_plan",
                    "targetReference":null,
                    "allowedChanges":"requested_plan",
                    "maxCandidates":1,
                    "allowNewTerms":true
                  },
                  "controlSource":"conversation",
                  "policyVersion":"chapter-reply-policy-v1",
                  "contextAuthorityVersion":"story-context-authority-v2",
                  "convergenceApplied":false
                }
                """);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        lenient().when(userConfigService.requireAvailableModelConfig())
                .thenReturn(new LlmProviderRuntimeConfig(
                        "deepseek",
                        "https://api.deepseek.com",
                        "test-key",
                        "deepseek-v4-flash"));
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.stream(any(), any())).thenReturn(new CompletedCall());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper,
                messageMapper,
                userConfigService,
                providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher,
                new LlmStreamCallRegistry()).run(12L);

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).stream(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().options().maxOutputTokens()).isEqualTo(4096);
        assertThat(requestCaptor.getValue().messages().get(0).content())
                .contains("结构化规划")
                .contains("不生成正文")
                .contains("不得宣称已经确认");
    }

    /**
     * 验证模型审计只保存策略安全摘要，不保存用户正文或完整 Prompt。
     */
    @Test
    void auditsSafePolicySummaryWithoutPrompt() {
        AiTaskEntity task = task("queued", 0);
        task.setTaskInputJson("""
                {
                  "schemaVersion":1,
                  "messageId":11,
                  "conversationId":8,
                  "replyMode":"compare",
                  "replyDepth":"brief",
                  "replyScope":{
                    "primaryIntent":"compare_candidates",
                    "targetType":"current_discussion",
                    "targetReference":"不要写入审计的用户范围文本",
                    "allowedChanges":"candidate_summaries",
                    "maxCandidates":3,
                    "allowNewTerms":false
                  },
                  "controlSource":"message",
                  "policyVersion":"chapter-reply-policy-v1",
                  "contextAuthorityVersion":"story-context-authority-v2",
                  "convergenceApplied":false
                }
                """);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        lenient().when(userConfigService.requireAvailableModelConfig())
                .thenReturn(new LlmProviderRuntimeConfig(
                        "deepseek",
                        "https://api.deepseek.com",
                        "test-key",
                        "deepseek-v4-flash"));
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.stream(any(), any())).thenReturn(new CompletedCall());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });
        new ConversationReplyTaskRunner(
                taskMapper,
                messageMapper,
                userConfigService,
                providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher,
                new LlmStreamCallRegistry(),
                null,
                null,
                new ObjectMapper()).run(12L);

        ArgumentCaptor<LlmCallContext> contextCaptor = ArgumentCaptor.forClass(LlmCallContext.class);
        verify(providerFactory).createObserved(any(), contextCaptor.capture());
        LlmCallContext context = contextCaptor.getValue();
        assertThat(context.aiTaskId()).isEqualTo(12L);
        assertThat(context.conversationId()).isEqualTo(8L);
        assertThat(context.replyMode()).isEqualTo("compare");
        assertThat(context.replyDepth()).isEqualTo("brief");
        assertThat(context.replyScopeSummary())
                .contains("compare_candidates")
                .doesNotContain("不要写入审计")
                .doesNotContain("请讨论本章目标");
    }

    /**
     * 验证 Provider 失败只发布安全错误且不持久化不完整回复。
     */
    @Test
    void recordsSafeProviderFailureWithoutPartialReply() {
        AiTaskEntity task = task("queued", 0);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        lenient().when(userConfigService.requireAvailableModelConfig())
                .thenReturn(new LlmProviderRuntimeConfig(
                        "deepseek",
                        "https://api.deepseek.com",
                        "test-key",
                        "deepseek-v4-flash"));
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.stream(any(), any())).thenReturn(new FailedCall());
        new ConversationReplyTaskRunner(
                taskMapper,
                messageMapper,
                userConfigService,
                providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher,
                new LlmStreamCallRegistry(),
                null,
                null,
                new ObjectMapper()).run(12L);

        verify(messageMapper, never()).insert(any(ChapterConversationMessageEntity.class));
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(eventCaptor.capture());
        ChapterReplyEvent failedEvent = eventCaptor.getAllValues().stream()
                .filter(ChapterReplyEvent.class::isInstance)
                .map(ChapterReplyEvent.class::cast)
                .filter(event -> "reply.failed".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(failedEvent.errorCode()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(failedEvent.errorMessage()).isEqualTo("依赖服务暂时不可用");
        assertThat(failedEvent.failure().diagnosticRef()).matches("diag_[0-9a-f]{32}");
        assertThat(failedEvent.failure().category()).isEqualTo("service_unavailable");
    }

    /**
     * 验证总结达到输出上限时整轮失败，不保存可能遗漏内容的半截总结。
     */
    @Test
    void rejectsTruncatedSummaryInsteadOfPersistingPartialContent() {
        AiTaskEntity task = task("queued", 0);
        task.setTaskInputJson("""
                {
                  "schemaVersion":3,
                  "messageId":11,
                  "conversationId":8,
                  "replyMode":"converge",
                  "replyDepth":"brief",
                  "replyScope":{
                    "primaryIntent":"converge_consensus",
                    "targetType":"current_discussion",
                    "targetReference":null,
                    "allowedChanges":"confirmed_and_pending_summary",
                    "maxCandidates":1,
                    "allowNewTerms":false
                  },
                  "controlSource":"message",
                  "policyVersion":"chapter-reply-policy-v5",
                  "contextAuthorityVersion":"story-context-authority-v2",
                  "convergenceApplied":true,
                  "deferredReplyDepth":null
                }
                """);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(providerFactory.create(any())).thenReturn(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("尚未完整的总结"));
            return new CompletedCall(new LlmResponseMetadata(
                    "deepseek", "test-model", "max_tokens", 100, 200, 300, "request-1"));
        }).when(provider).stream(any(), any());

        new ConversationReplyTaskRunner(
                taskMapper, messageMapper, userConfigService, providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher, new LlmStreamCallRegistry(), null, null, new ObjectMapper()).run(12L);

        verify(messageMapper, never()).insert(any(ChapterConversationMessageEntity.class));
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(eventCaptor.capture());
        ChapterReplyEvent failedEvent = eventCaptor.getAllValues().stream()
                .filter(ChapterReplyEvent.class::isInstance)
                .map(ChapterReplyEvent.class::cast)
                .filter(event -> "reply.failed".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(failedEvent.errorCode()).isEqualTo("CONVERSATION_REPLY_TRUNCATED");
    }

    /**
     * 验证无法恢复的结构化输出不会把原始 JSON 暴露为助手消息。
     */
    @Test
    void failsMalformedStructuredOutputWithoutPersistingRawJson() {
        AiTaskEntity task = task("queued", 0);
        task.setTaskInputJson("""
                {
                  "schemaVersion":3,
                  "messageId":11,
                  "conversationId":8,
                  "replyMode":"compare",
                  "replyDepth":"balanced",
                  "replyScope":{
                    "primaryIntent":"compare_candidates",
                    "targetType":"current_discussion",
                    "targetReference":null,
                    "allowedChanges":"candidate_summaries",
                    "maxCandidates":1,
                    "allowNewTerms":false
                  },
                  "controlSource":"message",
                  "policyVersion":"chapter-reply-policy-v5",
                  "contextAuthorityVersion":"story-context-authority-v2",
                  "convergenceApplied":false,
                  "deferredReplyDepth":null
                }
                """);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(providerFactory.create(any())).thenReturn(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("{\"回复\":"));
            return new CompletedCall();
        }).when(provider).stream(any(), any());

        new ConversationReplyTaskRunner(
                taskMapper, messageMapper, userConfigService, providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher, new LlmStreamCallRegistry(), null, null, new ObjectMapper()).run(12L);

        verify(messageMapper, never()).insert(any(ChapterConversationMessageEntity.class));
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(eventCaptor.capture());
        ChapterReplyEvent failedEvent = eventCaptor.getAllValues().stream()
                .filter(ChapterReplyEvent.class::isInstance)
                .map(ChapterReplyEvent.class::cast)
                .filter(event -> "reply.failed".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(failedEvent.errorCode()).isEqualTo("CONVERSATION_REPLY_JSON_INVALID");
    }

    /**
     * 验证 V5 总结按当前上下文消息数提高输出预算，采用深度仍保持简洁。
     */
    @Test
    void expandsSummaryBudgetWithoutChangingResolvedDepth() {
        AiTaskEntity task = task("queued", 0);
        task.setTaskInputJson("""
                {
                  "schemaVersion":3,
                  "messageId":11,
                  "conversationId":8,
                  "replyMode":"converge",
                  "replyDepth":"brief",
                  "replyScope":{
                    "primaryIntent":"converge_consensus",
                    "targetType":"current_discussion",
                    "targetReference":null,
                    "allowedChanges":"confirmed_and_pending_summary",
                    "maxCandidates":1,
                    "allowNewTerms":false
                  },
                  "controlSource":"message",
                  "policyVersion":"chapter-reply-policy-v5",
                  "contextAuthorityVersion":"story-context-authority-v2",
                  "convergenceApplied":true,
                  "deferredReplyDepth":null
                }
                """);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(messageMapper.selectCount(any())).thenReturn(20L);
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.stream(any(), any())).thenReturn(new CompletedCall());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper, messageMapper, userConfigService, providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher, new LlmStreamCallRegistry(), null, null, new ObjectMapper()).run(12L);

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).stream(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().options().maxOutputTokens()).isEqualTo(2816);
        ArgumentCaptor<LlmCallContext> contextCaptor = ArgumentCaptor.forClass(LlmCallContext.class);
        verify(providerFactory).createObserved(any(), contextCaptor.capture());
        assertThat(contextCaptor.getValue().replyDepth()).isEqualTo("brief");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void retryQueueRejectionKeepsExistingDiagnosticReference() {
        AiTaskEntity task = task("queued", 4);
        task.setDiagnosticRef("diag_existing_reply_ref");
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);

        new ConversationReplyTaskRunner(
                taskMapper, messageMapper, userConfigService, providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper), eventPublisher,
                new LlmStreamCallRegistry(), null, null, new ObjectMapper()).reject(12L);

        ArgumentCaptor<UpdateWrapper<AiTaskEntity>> updateCaptor =
                ArgumentCaptor.forClass((Class) UpdateWrapper.class);
        verify(taskMapper).update(any(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getParamNameValuePairs().values())
                .contains("diag_existing_reply_ref");
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(((ChapterReplyEvent) eventCaptor.getValue()).failure().diagnosticRef())
                .isEqualTo("diag_existing_reply_ref");
    }

    @Test
    void buildsAndSendsStoryContextBeforeStreaming() {
        AiTaskEntity task = task("queued", 0);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        lenient().when(userConfigService.requireAvailableModelConfig()).thenReturn(
                new LlmProviderRuntimeConfig("deepseek", "https://api.deepseek.com", "test-key", "deepseek-v4-flash"));
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.capabilities()).thenReturn(new LlmProviderCapabilities(true, true, false, 16384, 8192));
        when(contextBindingService.buildAndAttach(any(StoryContextBuildCommand.class), any(AiTaskEntity.class)))
                .thenAnswer(invocation -> {
                    StoryContextBuildCommand command = invocation.getArgument(0);
                    return new StoryContextSnapshot(
                            88L, "chapter_discussion:1:2:8", 1L, 2L, 8L,
                            StoryContextProfile.CHAPTER_DISCUSSION, 2, 1,
                            command.contextWindowTokens(), command.outputReserveTokens(),
                            command.inputBudgetTokens(), 120,
                            "hash", List.of(
                                    new StoryContextItem(StoryContextSourceType.SYSTEM_RULE, "system", "v1", null,
                                            "SYSTEM", "【必须遵守的规则】只把 AI 输出当作候选。",
                                            true, 1000, 0, 12, 12, "INCLUDED"),
                                    new StoryContextItem(StoryContextSourceType.TASK_RULE, "task", "v3", null,
                                            "SYSTEM", command.taskInstruction(),
                                            true, 990, 1, 80, 80, "INCLUDED"),
                                    new StoryContextItem(StoryContextSourceType.CONVERSATION_TURN, "5:user", "1", null,
                                            "USER", "我想让主角先救人。",
                                            false, 600, 400, 8, 8, "INCLUDED"),
                                    new StoryContextItem(StoryContextSourceType.CONVERSATION_TURN,
                                            "5:assistant", "1", null,
                                            "ASSISTANT", "可以让救人暴露主角能力。",
                                            false, 600, 401, 14, 14, "INCLUDED"),
                                    new StoryContextItem(StoryContextSourceType.USER_INPUT, "11", null, null,
                                            "USER", "讨论本章目标", true, 1000, 500, 6, 6, "INCLUDED")),
                            List.of(), null);
                });
        org.mockito.Mockito.doAnswer(invocation -> new CompletedCall())
                .when(provider).stream(any(), any());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper, messageMapper, userConfigService, providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher, new LlmStreamCallRegistry(), contextBindingService).run(12L);

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).stream(requestCaptor.capture(), any());
        LlmRequest request = requestCaptor.getValue();
        assertThat(request.messages()).extracting(message -> message.role())
                .containsExactly(LlmRole.SYSTEM, LlmRole.SYSTEM, LlmRole.USER,
                        LlmRole.ASSISTANT, LlmRole.USER);
        assertThat(request.messages()).extracting(message -> message.content())
                .containsExactly(
                        "【必须遵守的规则】只把 AI 输出当作候选。",
                        "使用自然、直接、容易理解的中文，回应作者当前提出的问题。"
                                + "本轮沿作者当前想法继续讨论；缺失信息会明显改变回答时再提问。"
                                + "本轮平衡回答，说明主要依据和直接影响，信息充分后收束。"
                                + "只沿当前回答方向展开，不在结尾追加多个可选分支。"
                                + "当前范围是本章正在讨论的问题。"
                                + "结合当前作品资料理解问题，以作者最新消息为准。"
                                + "复述作者内容时，保留原有的否定和不确定措辞。",
                        "我想让主角先救人。",
                        "可以让救人暴露主角能力。",
                        "讨论本章目标");
        assertThat(request.options().maxOutputTokens()).isEqualTo(1536);
        org.mockito.Mockito.verify(contextBindingService)
                .buildAndAttach(any(StoryContextBuildCommand.class), any(AiTaskEntity.class));
    }

    @Test
    void sendsFrozenProseObjectContextInProviderMessageSnapshot() {
        AiTaskEntity task = task("queued", 0);
        task.setTaskInputJson("""
                {"schemaVersion":3,"messageId":11,"conversationId":8,
                 "replyMode":"explore","replyDepth":"brief",
                 "replyScope":{"primaryIntent":"discuss","targetType":"current_discussion",
                 "targetReference":null,"allowedChanges":"discussion","maxCandidates":1,"allowNewTerms":true},
                 "controlSource":"message","policyVersion":"chapter-reply-policy-v1",
                 "contextAuthorityVersion":"story-context-authority-v2","convergenceApplied":false,
                 "proseObjectId":"candidate:16811","proseObjectVersion":4,
                 "proseContentHash":"frozen-hash",
                 "proseTargetText":"当前讨论对象：候选 1\\n来源：正式正文的有界改写\\n正文：冻结候选内容"}
                """);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.capabilities()).thenReturn(new LlmProviderCapabilities(true, true, false, 16384, 8192));
        when(contextBindingService.buildAndAttach(any(StoryContextBuildCommand.class), any(AiTaskEntity.class)))
                .thenAnswer(invocation -> {
                    StoryContextBuildCommand command = invocation.getArgument(0);
                    return new StoryContextSnapshot(
                            88L, "chapter_discussion:1:2:8", 1L, 2L, 8L,
                            StoryContextProfile.CHAPTER_DISCUSSION, 1, 1, 16384, 4096, 12288, 12,
                            "hash", List.of(
                                    new StoryContextItem(StoryContextSourceType.SYSTEM_RULE, "system", "v1", null,
                                            "SYSTEM", "系统规则", true, 1000, 0, 2, 2, "INCLUDED"),
                                    new StoryContextItem(StoryContextSourceType.TARGET_TEXT, "candidate:16811", "4", null,
                                            "USER", command.targetText(), true, 1000, 500, 20, 20, "INCLUDED"),
                                    new StoryContextItem(StoryContextSourceType.USER_INPUT, "11", null, null,
                                            "USER", command.currentInput(), true, 1000, 500, 3, 3, "INCLUDED")),
                            List.of(), null);
                });
        when(provider.stream(any(), any())).thenReturn(new CompletedCall());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper, messageMapper, userConfigService, providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher, new LlmStreamCallRegistry(), contextBindingService).run(12L);

        ArgumentCaptor<StoryContextBuildCommand> commandCaptor =
                ArgumentCaptor.forClass(StoryContextBuildCommand.class);
        verify(contextBindingService).buildAndAttach(commandCaptor.capture(), any(AiTaskEntity.class));
        assertThat(commandCaptor.getValue().targetText())
                .contains("当前讨论对象：候选 1", "来源：正式正文的有界改写", "正文：冻结候选内容");
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).stream(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().messages()).extracting(message -> message.content())
                .containsExactly("系统规则",
                        "当前讨论对象：候选 1\n来源：正式正文的有界改写\n正文：冻结候选内容",
                        "请讨论本章目标");
    }

    /**
     * 验证小上下文模型会压缩输出预算，并为输入上下文至少保留 1024 tokens。
     */
    @Test
    void capsDraftOutputBudgetToPreserveInputContext() {
        AiTaskEntity task = task("queued", 0);
        task.setTaskInputJson("""
                {
                  "schemaVersion":1,
                  "messageId":11,
                  "conversationId":8,
                  "replyMode":"draft",
                  "replyDepth":"deep",
                  "replyScope":{
                    "primaryIntent":"draft_prose",
                    "targetType":"chapter_draft",
                    "targetReference":null,
                    "allowedChanges":"requested_draft",
                    "maxCandidates":1,
                    "allowNewTerms":true
                  },
                  "controlSource":"message",
                  "policyVersion":"chapter-reply-policy-v1",
                  "contextAuthorityVersion":"story-context-authority-v2",
                  "convergenceApplied":false
                }
                """);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        lenient().when(userConfigService.requireAvailableModelConfig()).thenReturn(
                new LlmProviderRuntimeConfig("deepseek", "https://api.deepseek.com", "test-key", "small-context"));
        when(userConfigService.requireAvailableExecutionConfig()).thenReturn(executionConfig("small-context"));
        when(providerFactory.create(any())).thenReturn(provider);
        when(provider.capabilities()).thenReturn(new LlmProviderCapabilities(true, true, false, 4096, 8192));
        when(contextBindingService.buildAndAttach(any(StoryContextBuildCommand.class), any(AiTaskEntity.class)))
                .thenAnswer(invocation -> {
                    StoryContextBuildCommand command = invocation.getArgument(0);
                    return new StoryContextSnapshot(
                            88L, "chapter_discussion:1:2:8", 1L, 2L, 8L,
                            StoryContextProfile.CHAPTER_DISCUSSION, 2, 1,
                            command.contextWindowTokens(),
                            command.outputReserveTokens(),
                            command.inputBudgetTokens(),
                            12,
                            "hash",
                            List.of(new StoryContextItem(
                                    StoryContextSourceType.USER_INPUT,
                                    "11",
                                    null,
                                    null,
                                    "USER",
                                    "讨论本章目标",
                                    true,
                                    1000,
                                    500,
                                    3,
                                    3,
                                    "INCLUDED")),
                            List.of(),
                            null);
                });
        when(provider.stream(any(), any())).thenReturn(new CompletedCall());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper, messageMapper, userConfigService, providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher, new LlmStreamCallRegistry(), contextBindingService).run(12L);

        ArgumentCaptor<StoryContextBuildCommand> commandCaptor =
                ArgumentCaptor.forClass(StoryContextBuildCommand.class);
        verify(contextBindingService).buildAndAttach(commandCaptor.capture(), any(AiTaskEntity.class));
        assertThat(commandCaptor.getValue().contextWindowTokens()).isEqualTo(4096);
        assertThat(commandCaptor.getValue().outputReserveTokens()).isEqualTo(3072);
        assertThat(commandCaptor.getValue().inputBudgetTokens()).isEqualTo(1024);

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).stream(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().options().maxOutputTokens()).isEqualTo(3072);
    }

    private AiTaskEntity task(String status, int version) {
        AiTaskEntity task = new AiTaskEntity();
        task.setId(12L);
        task.setTaskType("conversation_reply");
        task.setTaskStatus(status);
        task.setWorkId(1L);
        task.setChapterId(2L);
        task.setVersion(version);
        task.setDeleted(0);
        return task;
    }

    private LlmExecutionConfig executionConfig(String model) {
        return new LlmExecutionConfig(
                new LlmProviderRuntimeConfig(
                        "deepseek",
                        "https://api.deepseek.com",
                        "test-key",
                        model),
                new LlmExecutionConfigDescriptor("deepseek", model, 1, 1));
    }

    @Test
    void convertsCurrentComparisonDraftToPersistedFrontendInteraction() {
        AiTaskEntity task = task("queued", 0);
        task.setTaskInputJson("""
                {
                  "schemaVersion":1,
                  "messageId":11,
                  "conversationId":8,
                  "replyMode":"compare",
                  "replyDepth":"balanced",
                  "replyScope":{
                    "primaryIntent":"compare_candidates",
                    "targetType":"current_discussion",
                    "targetReference":null,
                    "allowedChanges":"candidate_summaries",
                    "maxCandidates":2,
                    "allowNewTerms":false
                  },
                  "controlSource":"message",
                  "policyVersion":"chapter-reply-policy-v5",
                  "contextAuthorityVersion":"story-context-authority-v2",
                  "convergenceApplied":false
                }
                """);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(providerFactory.create(any())).thenReturn(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("""
                    {"回复":"两个方向的差别在风险出现的时间。","问题":"你更倾向哪个方向？",
                    "选项":[{"标题":"立即公开","说明":"冲突立即发生","取舍":"调查空间较少"},
                    {"标题":"暂时隐瞒","说明":"保留调查空间","取舍":"关系压力累积"}]}
                    """));
            return new CompletedCall();
        }).when(provider).stream(any(), any());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper,
                messageMapper,
                userConfigService,
                providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher,
                new LlmStreamCallRegistry(),
                null,
                null,
                new ObjectMapper()).run(12L);

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).stream(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().options().responseFormat()).isEqualTo(LlmResponseFormat.JSON_OBJECT);
        assertThat(requestCaptor.getValue().messages().get(0).content())
                .contains("‘回复’", "‘问题’", "‘选项’", "‘标题’", "‘说明’", "‘取舍’")
                .doesNotContain("{\"回复\"")
                .doesNotContain("schemaVersion", "single_choice", "questionId", "optionId", "allowCustom");

        ArgumentCaptor<ChapterConversationMessageEntity> messageCaptor =
                ArgumentCaptor.forClass(ChapterConversationMessageEntity.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("两个方向的差别在风险出现的时间。");
        assertThat(messageCaptor.getValue().getInteractionJson())
                .contains("\"schemaVersion\":1", "\"type\":\"single_choice\"", "\"questionId\":\"task-12\"")
                .contains("\"optionId\":\"option-1\"", "\"allowCustom\":true");
    }

    @Test
    void persistsAuthorCorrectionWithoutModelStatusLanguage() {
        AiTaskEntity task = task("queued", 0);
        task.setTaskInputJson("""
                {
                  "schemaVersion":1,
                  "messageId":11,
                  "conversationId":8,
                  "replyMode":"explore",
                  "replyDepth":"brief",
                  "replyScope":{
                    "primaryIntent":"explore_direction",
                    "targetType":"current_focus",
                    "targetReference":"失踪的是妹妹，不是姐姐",
                    "allowedChanges":"fact_correction",
                    "maxCandidates":1,
                    "allowNewTerms":false
                  },
                  "controlSource":"message",
                  "policyVersion":"chapter-reply-policy-v5",
                  "contextAuthorityVersion":"story-context-authority-v2",
                  "convergenceApplied":false
                }
                """);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(providerFactory.create(any())).thenReturn(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("好的，已确认：失踪的是妹妹，不是姐姐，其余不变。"));
            return new CompletedCall();
        }).when(provider).stream(any(), any());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper,
                messageMapper,
                userConfigService,
                providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher,
                new LlmStreamCallRegistry(),
                null,
                null,
                new ObjectMapper()).run(12L);

        ArgumentCaptor<ChapterConversationMessageEntity> messageCaptor =
                ArgumentCaptor.forClass(ChapterConversationMessageEntity.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("失踪的是妹妹，不是姐姐");
        verify(eventPublisher, never()).publishEvent(
                ChapterReplyEvent.delta(2L, 12L, 8L, "好的，已确认：失踪的是妹妹，不是姐姐，其余不变。"));
        verify(eventPublisher).publishEvent(ChapterReplyEvent.delta(2L, 12L, 8L, "失踪的是妹妹，不是姐姐"));
    }

    @Test
    void stripsChunkedCandidateStatusMarkersBeforePublishingAndPersistingReply() {
        AiTaskEntity task = task("queued", 0);
        when(taskMapper.selectById(12L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        when(providerFactory.create(any())).thenReturn(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<LlmStreamEvent> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamEvent.TextDelta("【尚未确认"));
            consumer.accept(new LlmStreamEvent.TextDelta(
                    "的候选，仅供讨论】【尚未确认的候选，仅供讨论】"));
            consumer.accept(new LlmStreamEvent.TextDelta("可以让车站出现空间错位。"));
            return new CompletedCall();
        }).when(provider).stream(any(), any());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });

        new ConversationReplyTaskRunner(
                taskMapper, messageMapper, userConfigService, providerFactory,
                new ConversationReplyPersistenceService(taskMapper, messageMapper),
                eventPublisher, new LlmStreamCallRegistry()).run(12L);

        ArgumentCaptor<ChapterConversationMessageEntity> messageCaptor =
                ArgumentCaptor.forClass(ChapterConversationMessageEntity.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("可以让车站出现空间错位。");
        verify(eventPublisher).publishEvent(
                ChapterReplyEvent.delta(2L, 12L, 8L, "可以让车站出现空间错位。"));
        verify(eventPublisher, never()).publishEvent(
                ChapterReplyEvent.delta(2L, 12L, 8L, "【尚未确认"));
    }

    private ChapterConversationMessageEntity userMessage() {
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setId(11L);
        message.setConversationId(8L);
        message.setChapterId(2L);
        message.setMessageRole("user");
        message.setContent("请讨论本章目标");
        message.setAiTaskId(12L);
        message.setDeleted(0);
        return message;
    }

    private static final class CompletedCall implements LlmStreamCall {

        private final LlmResponseMetadata metadata;

        private CompletedCall() {
            this(null);
        }

        private CompletedCall(LlmResponseMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(LlmStreamStatus.COMPLETED, metadata, null);
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }

    private static final class CanceledCall implements LlmStreamCall {

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(LlmStreamStatus.CANCELED, null, null);
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }

    private static final class FailedCall implements LlmStreamCall {

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(
                    LlmStreamStatus.FAILED,
                    null,
                    LlmProviderError.SERVICE_UNAVAILABLE);
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }
}
