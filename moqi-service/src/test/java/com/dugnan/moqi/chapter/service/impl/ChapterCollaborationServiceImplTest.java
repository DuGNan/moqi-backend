package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageCreated;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageInteractionResponse;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.BriefRequest;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.DiscussionFocusRequest;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineRequest;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ReplyControlRequest;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.SendMessageRequest;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.focus.ChapterDiscussionFocusResolver;
import com.dugnan.moqi.chapter.focus.ResolvedDiscussionFocus;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.policy.DefaultReplyPolicyResolver;
import com.dugnan.moqi.chapter.policy.ReplyConversationSignals;
import com.dugnan.moqi.chapter.policy.ReplyDepth;
import com.dugnan.moqi.chapter.policy.ReplyMode;
import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceService;
import com.dugnan.moqi.chapter.policy.ReplyScope;
import com.dugnan.moqi.chapter.policy.ResolvedReplyPolicy;
import com.dugnan.moqi.chapter.stream.ConversationReplyTaskSubmittedEvent;
import com.dugnan.moqi.chapter.service.ProseObjectTargetService;
import com.dugnan.moqi.chapter.service.ProseObjectTargetService.ProseObjectTarget;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author dgn
 * @date 2026-07-16
 * @description 验证章节共创服务的会话、消息和大纲规则。
 */
@ExtendWith(MockitoExtension.class)
class ChapterCollaborationServiceImplTest {

    @Mock
    private WorkMapper workMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private ChapterConversationMapper conversationMapper;
    @Mock
    private ChapterConversationMessageMapper messageMapper;
    @Mock
    private ChapterBriefMapper briefMapper;
    @Mock
    private ChapterOutlineQueryMapper outlineMapper;
    @Mock
    private AiTaskMapper aiTaskMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ChapterDiscussionFocusResolver focusResolver;
    @Mock
    private ReplyPolicyPreferenceService replyPolicyService;
    @Mock
    private ProseObjectTargetService proseObjectTargetService;

    private ChapterCollaborationServiceImpl service;

    /**
     * 初始化章节共创服务。
     */
    @BeforeEach
    void setUp() {
        service = new ChapterCollaborationServiceImpl(
                workMapper,
                chapterMapper,
                conversationMapper,
                messageMapper,
                briefMapper,
                outlineMapper,
                aiTaskMapper,
                eventPublisher,
                focusResolver,
                null,
                null,
                null,
                proseObjectTargetService);
    }

    /**
     * 验证创建会话时复用同一章节下的活动会话。
     */
    @Test
    void reusesActiveConversationForChapter() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        ChapterConversationEntity conversation = conversation(8L, 1L, 2L);
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));

        var result = service.createOrGetConversation(2L);

        assertThat(result.id()).isEqualTo(8L);
        assertThat(result.conversationStatus()).isEqualTo("active");
    }

    /**
     * 验证没有消息引用时仍能读取旧会话消息。
     */
    @Test
    void listsLegacyMessagesWithoutReferences() {
        when(conversationMapper.selectById(8L)).thenReturn(conversation(8L, 1L, 2L));
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setId(11L);
        message.setConversationId(8L);
        message.setChapterId(2L);
        message.setMessageRole("user");
        message.setContent("旧会话消息");
        message.setDeleted(0);
        when(messageMapper.selectList(any())).thenReturn(List.of(message));

        var result = service.listMessages(8L);

        assertThat(result.messages()).singleElement().satisfies(detail -> {
            assertThat(detail.id()).isEqualTo(11L);
            assertThat(detail.referencedMessageId()).isNull();
            assertThat(detail.referencedMessage()).isNull();
        });
    }

    /**
     * 验证选区协助任务不会被误解析为普通会话回复策略。
     */
    @Test
    void listsSelectionAssistanceMessagesWithoutReplyPolicy() {
        when(conversationMapper.selectById(8L)).thenReturn(conversation(8L, 1L, 2L));
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setId(11L);
        message.setConversationId(8L);
        message.setChapterId(2L);
        message.setMessageRole("assistant");
        message.setContent("已生成润色提案");
        message.setAiTaskId(12L);
        message.setDeleted(0);
        when(messageMapper.selectList(any())).thenReturn(List.of(message));

        AiTaskEntity task = new AiTaskEntity();
        task.setId(12L);
        task.setTaskType("chapter_selection_assistance_v1");
        task.setTaskInputJson("{\"operation\":\"polish\",\"inputFingerprint\":\"fingerprint\"}");
        when(aiTaskMapper.selectBatchIds(List.of(12L))).thenReturn(List.of(task));

        var result = service.listMessages(8L);

        assertThat(result.messages()).singleElement().satisfies(detail -> {
            assertThat(detail.aiTaskId()).isEqualTo(12L);
            assertThat(detail.effectiveReplyPolicy()).isNull();
        });
    }

    /**
     * 验证发送消息时可创建 AI 任务并回填消息任务 ID。
     */
    @Test
    void createsAiTaskWhenMessageRequestsIt() {
        when(conversationMapper.selectById(8L)).thenReturn(conversation(8L, 1L, 2L));
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            entity.setGmtCreate(LocalDateTime.now());
            entity.setGmtModified(entity.getGmtCreate());
            return 1;
        });
        when(aiTaskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            AiTaskEntity entity = invocation.getArgument(0);
            entity.setId(12L);
            return 1;
        });

        MessageCreated result = service.sendMessage(
                8L,
                new SendMessageRequest("user", "讨论本章目标", true));

        assertThat(result.id()).isEqualTo(11L);
        assertThat(result.aiTaskId()).isEqualTo(12L);
        verify(messageMapper).updateById(any(ChapterConversationMessageEntity.class));
        verify(eventPublisher).publishEvent(new ConversationReplyTaskSubmittedEvent(12L));
        verify(aiTaskMapper).insert(org.mockito.ArgumentMatchers.<AiTaskEntity>argThat(task ->
                task.getResultMessageId() == null
                        && task.getTaskInputJson().contains("\"schemaVersion\":3")
                        && task.getTaskInputJson().contains("\"messageId\":11")
                        && task.getTaskInputJson().contains("\"replyMode\":\"explore\"")
                        && task.getTaskInputJson().contains("\"policyVersion\":\"chapter-reply-policy-v5\"")
                        && !task.getTaskInputJson().contains("讨论本章目标")));
    }

    @Test
    void freezesProseObjectTargetAndReusesClientMessageId() {
        ChapterConversationEntity scoped = conversation(8L, 1L, 2L);
        scoped.setConversationType("prose_object");
        scoped.setTargetObjectId("candidate:18");
        when(conversationMapper.selectById(8L)).thenReturn(scoped);
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        when(proseObjectTargetService.resolve(2L, "candidate:18")).thenReturn(
                new ProseObjectTarget("candidate:18", "candidate", 4, "content-hash", "候选正文", "由改写形成"));
        java.util.concurrent.atomic.AtomicReference<ChapterConversationMessageEntity> persisted =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(messageMapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity value = invocation.getArgument(0);
            value.setId(11L);
            persisted.set(value);
            return 1;
        });
        when(aiTaskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiTaskEntity.class).setId(12L);
            return 1;
        });

        SendMessageRequest request = new SendMessageRequest(
                "user", "讨论这份候选", true, null, null, null, null, "client-message-1");
        MessageCreated first = service.sendMessage(8L, request);
        MessageCreated replay = service.sendMessage(8L, request);

        assertThat(replay.id()).isEqualTo(first.id());
        verify(messageMapper, org.mockito.Mockito.times(1)).insert(any(ChapterConversationMessageEntity.class));
        verify(aiTaskMapper, org.mockito.Mockito.times(1)).insert(org.mockito.ArgumentMatchers.argThat((AiTaskEntity task) ->
                task.getTaskInputJson().contains("\"proseObjectId\":\"candidate:18\"")
                        && task.getTaskInputJson().contains("\"proseContentHash\":\"content-hash\"")
                        && task.getTaskInputJson().contains("候选正文")));
    }

    @Test
    void persistsReadableStructuredAnswerAndRejectsForgedQuestion() {
        when(conversationMapper.selectById(8L)).thenReturn(conversation(8L, 1L, 2L));
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        ChapterConversationMessageEntity source = new ChapterConversationMessageEntity();
        source.setId(20L);
        source.setConversationId(8L);
        source.setChapterId(2L);
        source.setMessageRole("assistant");
        source.setDeleted(0);
        source.setInteractionJson("""
                {"schemaVersion":1,"type":"single_choice","questionId":"q-1","question":"代价？",
                "allowCustom":true,"options":[{"optionId":"a","title":"记忆","description":"","tradeoffs":""},
                {"optionId":"b","title":"疼痛","description":"","tradeoffs":""}]}
                """);
        when(messageMapper.selectById(20L)).thenReturn(source);
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity entity = invocation.getArgument(0);
            entity.setId(21L);
            return 1;
        });

        MessageCreated created = service.sendMessage(8L, new SendMessageRequest(
                "user", "客户端不可作为权威正文", false, null, null, 20L,
                new MessageInteractionResponse(1, "q-1", "a", "保留面孔")));

        assertThat(created.content()).isEqualTo("我选择“记忆”。补充：保留面孔");
        assertThat(created.interactionResponse()).isEqualTo(
                new MessageInteractionResponse(1, "q-1", "a", "保留面孔"));
        assertThatThrownBy(() -> service.sendMessage(8L, new SendMessageRequest(
                "user", "", false, null, null, 20L,
                new MessageInteractionResponse(1, "forged", "a", null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("来源问题不一致");
    }

    /**
     * 验证仅直接回答最新澄清问题时，服务层才把暂存深度交回策略解析器。
     */
    @Test
    void restoresDeferredDepthForDirectClarificationAnswer() {
        ChapterCollaborationServiceImpl policyAwareService = new ChapterCollaborationServiceImpl(
                workMapper, chapterMapper, conversationMapper, messageMapper, briefMapper, outlineMapper,
                aiTaskMapper, eventPublisher, focusResolver, null, replyPolicyService, new ObjectMapper());
        when(conversationMapper.selectById(8L)).thenReturn(conversation(8L, 1L, 2L));
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        ChapterConversationMessageEntity clarification = new ChapterConversationMessageEntity();
        clarification.setId(20L);
        clarification.setConversationId(8L);
        clarification.setChapterId(2L);
        clarification.setMessageRole("assistant");
        clarification.setAiTaskId(19L);
        clarification.setContent("目前无法确定你指的是哪一项。");
        clarification.setInteractionJson("""
                {"schemaVersion":1,"type":"open_question","questionId":"task-19",
                "question":"具体指的是哪一项？","options":[],"allowCustom":true}
                """);
        clarification.setDeleted(0);
        AiTaskEntity clarificationTask = new AiTaskEntity();
        clarificationTask.setId(19L);
        clarificationTask.setTaskType("conversation_reply");
        clarificationTask.setTaskInputJson("""
                {"schemaVersion":3,"messageId":18,"conversationId":8,
                "replyMode":"clarify","replyDepth":"brief",
                "replyScope":{"primaryIntent":"clarify_direction","targetType":"current_discussion",
                "targetReference":null,"allowedChanges":"question_only","maxCandidates":1,
                "allowNewTerms":false},"controlSource":"clarification",
                "policyVersion":"chapter-reply-policy-v5",
                "contextAuthorityVersion":"story-context-authority-v2","convergenceApplied":false,
                "deferredReplyDepth":"deep"}
                """);
        when(messageMapper.selectById(20L)).thenReturn(clarification);
        when(messageMapper.selectOne(any())).thenReturn(clarification);
        when(messageMapper.selectCount(any())).thenReturn(1L);
        when(aiTaskMapper.selectById(19L)).thenReturn(clarificationTask);
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(21L);
            return 1;
        });
        when(aiTaskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            AiTaskEntity task = invocation.getArgument(0);
            task.setId(22L);
            return 1;
        });
        when(replyPolicyService.resolve(eq(8L), eq("我指的是第二个方向"), any(), any()))
                .thenReturn(new ResolvedReplyPolicy(
                        ReplyMode.EXPLORE,
                        ReplyDepth.DEEP,
                        new ReplyScope(
                                "explore_direction", "current_discussion", null,
                                "discussion_expansion", 1, false),
                        "clarification",
                        DefaultReplyPolicyResolver.POLICY_VERSION,
                        false,
                        ReplyMode.CLARIFY,
                        true,
                        false,
                        null));

        policyAwareService.sendMessage(8L, new SendMessageRequest(
                "user", "", true, null, null, 20L,
                new MessageInteractionResponse(1, "task-19", null, "我指的是第二个方向")));

        ArgumentCaptor<ReplyConversationSignals> signalsCaptor =
                ArgumentCaptor.forClass(ReplyConversationSignals.class);
        verify(replyPolicyService).resolve(
                eq(8L), eq("我指的是第二个方向"), eq(null), signalsCaptor.capture());
        assertThat(signalsCaptor.getValue().previousMode()).isEqualTo(ReplyMode.CLARIFY);
        assertThat(signalsCaptor.getValue().directClarificationAnswer()).isTrue();
        assertThat(signalsCaptor.getValue().deferredDepth()).isEqualTo(ReplyDepth.DEEP);
        verify(aiTaskMapper).insert(org.mockito.ArgumentMatchers.<AiTaskEntity>argThat(task ->
                task.getTaskInputJson().contains("\"replyDepth\":\"deep\"")
                        && task.getTaskInputJson().contains("\"controlSource\":\"clarification\"")));
    }

    /**
     * 继续展开不能引用其他会话中的助手消息。
     */
    @Test
    void rejectsContinuationFromAnotherConversation() {
        when(conversationMapper.selectById(8L)).thenReturn(conversation(8L, 1L, 2L));
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        ChapterConversationMessageEntity assistant = new ChapterConversationMessageEntity();
        assistant.setId(91L);
        assistant.setConversationId(9L);
        assistant.setMessageRole("assistant");
        assistant.setAiTaskId(19L);
        assistant.setDeleted(0);
        when(messageMapper.selectById(91L)).thenReturn(assistant);

        assertThatThrownBy(() -> service.sendMessage(8L, new SendMessageRequest(
                "user", "继续展开上一轮内容", true, null,
                new ReplyControlRequest("deep", "auto", null, 91L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("continuationMessageId");
        verify(messageMapper, never()).insert(any(ChapterConversationMessageEntity.class));
    }

    /**
     * 继续展开不能引用未成功完成的助手任务。
     */
    @Test
    void rejectsContinuationFromFailedAssistantReply() {
        when(conversationMapper.selectById(8L)).thenReturn(conversation(8L, 1L, 2L));
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        ChapterConversationMessageEntity assistant = new ChapterConversationMessageEntity();
        assistant.setId(92L);
        assistant.setConversationId(8L);
        assistant.setMessageRole("assistant");
        assistant.setAiTaskId(20L);
        assistant.setDeleted(0);
        AiTaskEntity failed = new AiTaskEntity();
        failed.setId(20L);
        failed.setTaskStatus("failed");
        when(messageMapper.selectById(92L)).thenReturn(assistant);
        when(aiTaskMapper.selectById(20L)).thenReturn(failed);

        assertThatThrownBy(() -> service.sendMessage(8L, new SendMessageRequest(
                "user", "继续展开上一轮内容", true, null,
                new ReplyControlRequest("deep", "auto", null, 92L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("continuationMessageId");
        verify(messageMapper, never()).insert(any(ChapterConversationMessageEntity.class));
    }

    /**
     * 验证消息只持久化服务端校验后的 Brief 与待决键引用。
     */
    @Test
    void persistsValidatedDiscussionFocusReferences() {
        when(conversationMapper.selectById(8L)).thenReturn(conversation(8L, 1L, 2L));
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        when(focusResolver.resolve(2L, 8L, 21L, "protagonist_choice"))
                .thenReturn(new ResolvedDiscussionFocus(
                        21L,
                        0,
                        "protagonist_choice",
                        "主角选择",
                        "救人还是追击",
                        "",
                        "{}",
                        List.of()));
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(13L);
            return 1;
        });

        service.sendMessage(
                8L,
                new SendMessageRequest(
                        "user",
                        "我倾向先救人",
                        false,
                        new DiscussionFocusRequest(21L, "protagonist_choice")));

        ArgumentCaptor<ChapterConversationMessageEntity> captor =
                ArgumentCaptor.forClass(ChapterConversationMessageEntity.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getFocusBriefId()).isEqualTo(21L);
        assertThat(captor.getValue().getFocusDecisionKey()).isEqualTo("protagonist_choice");
        verify(focusResolver).resolve(eq(2L), eq(8L), eq(21L), eq("protagonist_choice"));
    }

    @Test
    void persistsReferenceToVisibleAssistantMessage() {
        when(conversationMapper.selectById(8L)).thenReturn(conversation(8L, 1L, 2L));
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        ChapterConversationMessageEntity referenced = new ChapterConversationMessageEntity();
        referenced.setId(31L);
        referenced.setConversationId(8L);
        referenced.setChapterId(2L);
        referenced.setMessageRole("assistant");
        referenced.setContent("可引用的回复");
        referenced.setDeleted(0);
        when(messageMapper.selectById(31L)).thenReturn(referenced);
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(32L);
            return 1;
        });

        service.sendMessage(8L, new SendMessageRequest("user", "请展开这一点", false, null, null, 31L));

        ArgumentCaptor<ChapterConversationMessageEntity> captor =
                ArgumentCaptor.forClass(ChapterConversationMessageEntity.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getReferencedMessageId()).isEqualTo(31L);
    }

    @Test
    void rejectsDeletedOrOutOfScopeMessageReferenceWithoutLeakingContent() {
        when(conversationMapper.selectById(8L)).thenReturn(conversation(8L, 1L, 2L));
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        ChapterConversationMessageEntity referenced = new ChapterConversationMessageEntity();
        referenced.setId(31L);
        referenced.setConversationId(9L);
        referenced.setChapterId(2L);
        referenced.setMessageRole("assistant");
        referenced.setContent("不应泄露的消息正文");
        referenced.setDeleted(0);
        when(messageMapper.selectById(31L)).thenReturn(referenced);

        assertThatThrownBy(() -> service.sendMessage(
                8L, new SendMessageRequest("user", "请展开这一点", false, null, null, 31L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MESSAGE_REFERENCE_INVALID);
        verify(messageMapper, never()).insert(any(ChapterConversationMessageEntity.class));
    }

    /**
     * 验证大纲保存会拒绝过期 revision。
     */
    @Test
    void rejectsStaleOutlineRevision() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        ChapterOutlineEntity outline = new ChapterOutlineEntity();
        outline.setId(5L);
        outline.setWorkId(1L);
        outline.setChapterId(2L);
        outline.setOutlineStatus("draft");
        outline.setOutlineContent("{\"title\":\"旧大纲\"}");
        outline.setRevision(3);
        outline.setDeleted(0);
        when(outlineMapper.findLatest(2L)).thenReturn(outline);
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed"))
                .thenReturn(confirmedBrief(5L));

        assertThatThrownBy(() -> service.saveOutline(
                2L,
                new OutlineRequest("{\"title\":\"新大纲\"}", "draft", 2)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OUTLINE_REVISION_CONFLICT);
    }

    /**
     * 验证旧 Brief 保存接口也会追加草稿，不覆盖历史记录。
     */
    @Test
    void appendsLegacyBriefDraft() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        when(briefMapper.insert(any(ChapterBriefEntity.class))).thenAnswer(invocation -> {
            ChapterBriefEntity brief = invocation.getArgument(0);
            brief.setId(5L);
            return 1;
        });

        var result = service.saveLatestBrief(2L, new BriefRequest("本章目标", "draft"));

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.briefStatus()).isEqualTo("draft");
        verify(briefMapper, never()).updateById(any(ChapterBriefEntity.class));
    }

    /**
     * 验证保存大纲会绑定显式选择的已确认 Brief。
     */
    @Test
    void bindsConfirmedBriefWhenSavingOutline() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        when(briefMapper.findByIdAndChapterId(5L, 2L)).thenReturn(confirmedBrief(5L));
        when(outlineMapper.findLatest(2L)).thenReturn(null);
        when(outlineMapper.insert(any(ChapterOutlineEntity.class))).thenAnswer(invocation -> {
            ChapterOutlineEntity outline = invocation.getArgument(0);
            outline.setId(7L);
            return 1;
        });

        var result = service.saveOutline(
                2L,
                new OutlineRequest("推进目标", "draft", 0, 5L));

        assertThat(result.confirmedBriefId()).isEqualTo(5L);
    }

    /**
     * 构造测试用作品。
     *
     * @param id 作品 ID
     * @return 作品实体
     */
    private WorkEntity work(Long id) {
        WorkEntity work = new WorkEntity();
        work.setId(id);
        work.setTitle("作品");
        work.setDeleted(0);
        return work;
    }

    /**
     * 构造测试用章节。
     *
     * @param id 章节 ID
     * @param workId 作品 ID
     * @return 章节实体
     */
    private ChapterEntity chapter(Long id, Long workId) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(id);
        chapter.setWorkId(workId);
        chapter.setTitle("章节");
        chapter.setDeleted(0);
        return chapter;
    }

    /**
     * 构造测试用会话。
     *
     * @param id 会话 ID
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @return 会话实体
     */
    private ChapterConversationEntity conversation(Long id, Long workId, Long chapterId) {
        ChapterConversationEntity conversation = new ChapterConversationEntity();
        conversation.setId(id);
        conversation.setWorkId(workId);
        conversation.setChapterId(chapterId);
        conversation.setConversationType("chapter_co_creation");
        conversation.setConversationStatus("active");
        conversation.setDeleted(0);
        return conversation;
    }

    /**
     * 构造测试已确认 Brief。
     *
     * @param id Brief ID
     * @return Brief 实体
     */
    private ChapterBriefEntity confirmedBrief(Long id) {
        ChapterBriefEntity brief = new ChapterBriefEntity();
        brief.setId(id);
        brief.setWorkId(1L);
        brief.setChapterId(2L);
        brief.setBriefStatus("confirmed");
        brief.setBriefContent("已确认共识");
        brief.setVersion(1);
        brief.setDeleted(0);
        return brief;
    }
}
