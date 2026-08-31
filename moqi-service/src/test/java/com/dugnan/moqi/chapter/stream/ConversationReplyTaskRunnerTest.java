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
        StoryContextSnapshot snapshot = new StoryContextSnapshot(
                88L, "chapter_discussion:1:2:8", 1L, 2L, 8L,
                StoryContextProfile.CHAPTER_DISCUSSION, 1, 1, 16384, 4096, 12288, 12,
                "hash", List.of(
                        new StoryContextItem(StoryContextSourceType.SYSTEM_RULE, "system", "v1", null,
                                "SYSTEM", "系统规则", true, 1000, 0, 2, 2, "INCLUDED"),
                        new StoryContextItem(StoryContextSourceType.USER_INPUT, "11", null, null,
                                "USER", "讨论本章目标", true, 1000, 500, 3, 3, "INCLUDED")),
                List.of(), null);
        when(contextBindingService.buildAndAttach(any(StoryContextBuildCommand.class), any(AiTaskEntity.class)))
                .thenReturn(snapshot);
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
        assertThat(requestCaptor.getValue().messages()).extracting(message -> message.content())
                .containsExactly("系统规则", "讨论本章目标");
        assertThat(requestCaptor.getValue().options().maxOutputTokens()).isEqualTo(4096);
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

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public LlmStreamResult await() {
            return new LlmStreamResult(LlmStreamStatus.COMPLETED, null, null);
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
