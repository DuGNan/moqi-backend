package com.dugnan.moqi.chapter.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.AcceptRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.CreateRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ModelPlanningProposal;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.PlanningContext;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefPreview;
import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.chapter.service.ProseObjectPromptContextService;
import com.dugnan.moqi.context.StoryContextBuildCommand;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextTaskBindingService;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderCapabilities;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证选区候选的发布边界、正文一致性校验和乐观锁采纳。
 */
class SelectionAssistanceServiceImplTest {

    @Test
    void compilesNaturalLanguageModelPromptWithoutInternalRoutingEnums() throws Exception {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity assistance = fixture.candidate("原文", "候选");
        assistance.setOperationType("rewrite");
        assistance.setUserInstruction("让冲突更集中");
        assistance.setAdjacentBefore("前文");
        assistance.setAdjacentAfter("后文");
        assistance.setBriefContent("作者已确认本章目标，其他内容仍为候选。");
        assistance.setPlanningContextJson(new ObjectMapper().writeValueAsString(
                new PlanningContext(10L, 2, 3, 20L, 4, "共 1 场", List.of())));
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(assistance);

        String prompt = fixture.service.modelPrompt(9L);

        assertThat(prompt)
                .contains("本轮任务：按作者要求重写正文")
                .contains("作者要求：让冲突更集中")
                .contains("当前权威场景规划摘要")
                .contains("所有正文和规划输出都只是候选")
                .doesNotContain("operation", "rewrite", "targetKind", "requestStatus", "baseScenePlanId");
    }

    @Test
    void buildsProseObjectSnapshotWithCurrentRequestAndFrozenBasis() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity assistance = fixture.candidate("原文", "候选");
        assistance.setRequestContractVersion(2);
        assistance.setConversationId(30L);
        assistance.setUserMessageId(31L);
        assistance.setTargetObjectId("candidate:8");
        assistance.setTargetKind("candidate");
        assistance.setBriefContent("本章必须拿到钥匙");
        assistance.setAdjacentBefore("前文");
        assistance.setAdjacentAfter("后文");
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(assistance);
        ChapterConversationMessageEntity current = new ChapterConversationMessageEntity();
        current.setId(31L);
        current.setConversationId(30L);
        current.setChapterId(2L);
        current.setMessageRole("user");
        current.setContent("改写\n作者要求：让选择更艰难");
        current.setDeleted(0);
        when(fixture.messageMapper.selectById(31L)).thenReturn(current);
        AiTaskEntity task = new AiTaskEntity();
        task.setId(8L);
        task.setTaskStatus("running");
        task.setVersion(1);
        task.setTaskInputJson("""
                {"proseObjectContext":"作者当前保存的正文：候选正文\\n候选创建时冻结依据：主角当时不知道真相"}
                """);
        when(fixture.taskMapper.selectById(8L)).thenReturn(task);
        ProseObjectPromptContextService promptContextService = mock(ProseObjectPromptContextService.class);
        StoryContextTaskBindingService bindingService = mock(StoryContextTaskBindingService.class);
        StoryContextSnapshot expected = mock(StoryContextSnapshot.class);
        when(bindingService.buildAndAttach(any(), any())).thenReturn(expected);
        fixture.service.setPromptContextDependencies(promptContextService, bindingService);
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.capabilities()).thenReturn(new LlmProviderCapabilities(true, false, false, 16384, 8192));

        StoryContextSnapshot actual = fixture.service.buildModelContext(
                9L, provider, "只生成待作者确认的修改提案");

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<StoryContextBuildCommand> command = ArgumentCaptor.forClass(StoryContextBuildCommand.class);
        verify(bindingService).buildAndAttach(command.capture(), org.mockito.ArgumentMatchers.same(task));
        assertThat(command.getValue().profile()).isEqualTo(com.dugnan.moqi.context.StoryContextProfile.PROSE_DISCUSSION);
        assertThat(command.getValue().currentInput()).isEqualTo("改写\n作者要求：让选择更艰难");
        assertThat(command.getValue().targetText())
                .contains("本章必须拿到钥匙", "候选创建时冻结依据", "候选正文")
                .doesNotContain("candidate:8", "hash");
        verify(promptContextService, never()).freeze(any(), any(), any());
    }

    @Test
    void rejectsModificationForPublishedChapterBeforeCallingModel() {
        Fixture fixture = new Fixture();
        ChapterEntity chapter = fixture.chapter("done", "原始正文");
        when(fixture.chapterMapper.selectById(2L)).thenReturn(chapter);
        CreateRequest request = new CreateRequest(3, hash("原始正文"), 0, 2, "原始", "rewrite", "更凝练", null, "k1");

        assertThatThrownBy(() -> fixture.service.create(2L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已发布章节只允许讨论");
    }

    @Test
    void allowsPublishedChapterDiscussionAndCreatesRecoverableAgentRun() {
        Fixture fixture = new Fixture();
        ChapterEntity chapter = fixture.chapter("done", "原始正文");
        when(fixture.chapterMapper.selectById(2L)).thenReturn(chapter);
        when(fixture.briefService.preview(2L, null)).thenReturn(new GenerationBriefPreview(
                1L, 2L, 3L, 1, "brief-v1", "current", List.of(), "b".repeat(64), null, "生成说明"));
        doAnswer(invocation -> {
            ((AiTaskEntity) invocation.getArgument(0)).setId(8L);
            return 1;
        }).when(fixture.taskMapper).insert(any(AiTaskEntity.class));
        AtomicReference<ChapterSelectionAssistanceEntity> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            ChapterSelectionAssistanceEntity entity = invocation.getArgument(0);
            entity.setId(9L);
            inserted.set(entity);
            return 1;
        }).when(fixture.assistanceMapper).insert(any(ChapterSelectionAssistanceEntity.class));
        when(fixture.assistanceMapper.selectById(9L)).thenAnswer(invocation -> inserted.get());
        when(fixture.assistanceMapper.update(any(), any())).thenReturn(1);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.start(any())).thenReturn(new AgentRunView(7L, SelectionAssistanceServiceImpl.WORKFLOW_TYPE,
                "queued", 1L, 2L, 8L, SelectionAssistanceServiceImpl.GENERATE_STEP, null, null, null, null, null, null));
        fixture.service.setAgentRuntime(runtime);

        fixture.service.create(2L, new CreateRequest(3, hash("原始正文"), 0, 2, "原始", "discuss",
                "分析节奏", null, "discussion-1"));

        verify(runtime).start(any());
        verify(fixture.assistanceMapper).insert(any(ChapterSelectionAssistanceEntity.class));
    }

    @Test
    void repeatsPersistedIdempotentRequestWithoutRecompilingChangedSources() {
        Fixture fixture = new Fixture();
        ChapterEntity changedChapter = fixture.chapter("done", "已经变化");
        changedChapter.setVersion(4);
        ChapterSelectionAssistanceEntity existing = fixture.candidate("原始", "保留停顿");
        existing.setOperationType("discuss");
        existing.setRequestStatus("ready");
        existing.setBaseContentHash(hash("原始正文"));
        existing.setSelectionStart(0);
        existing.setSelectionEnd(2);
        existing.setUserInstruction("分析节奏");
        existing.setIdempotencyKey("discussion-1");
        when(fixture.chapterMapper.selectById(2L)).thenReturn(changedChapter);
        when(fixture.assistanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        fixture.service.create(2L, new CreateRequest(3, hash("原始正文"), 0, 2, "原始", "discuss",
                "分析节奏", null, "discussion-1"));

        verify(fixture.briefService, never()).preview(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"discuss", "rewrite", "polish", "expand", "compress"})
    void acceptsEverySupportedOperationAtTheRequestBoundary(String operation) {
        Fixture fixture = new Fixture();
        ChapterEntity chapter = fixture.chapter("co_creation", "已经变化");
        chapter.setVersion(4);
        ChapterSelectionAssistanceEntity existing = fixture.idempotentRecord(operation, "operation-key");
        when(fixture.chapterMapper.selectById(2L)).thenReturn(chapter);
        when(fixture.assistanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        fixture.service.create(2L, new CreateRequest(3, hash("原始正文"), 0, 2, "原始", operation,
                "保持事实", null, "operation-key"));

        verify(fixture.briefService, never()).preview(any(), any());
    }

    @Test
    void rejectsUnknownOperation() {
        Fixture fixture = new Fixture();
        when(fixture.chapterMapper.selectById(2L)).thenReturn(fixture.chapter("co_creation", "原始正文"));

        assertThatThrownBy(() -> fixture.service.create(2L, new CreateRequest(
                3, hash("原始正文"), 0, 2, "原始", "translate", "翻译", null, "invalid-operation")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须符合契约");
    }

    @Test
    void rejectsBaseVersionAndFullContentHashConflicts() {
        Fixture fixture = new Fixture();
        when(fixture.chapterMapper.selectById(2L)).thenReturn(fixture.chapter("co_creation", "原始正文"));

        assertThatThrownBy(() -> fixture.service.create(2L, new CreateRequest(
                2, hash("原始正文"), 0, 2, "原始", "rewrite", "凝练", null, "version-conflict")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本或哈希已变化");
        assertThatThrownBy(() -> fixture.service.create(2L, new CreateRequest(
                3, hash("其他正文"), 0, 2, "原始", "rewrite", "凝练", null, "hash-conflict")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本或哈希已变化");
    }

    @Test
    void rejectsParentFromAnotherChapterOrWork() {
        Fixture fixture = new Fixture();
        ChapterEntity chapter = fixture.chapter("co_creation", "原始正文");
        ChapterSelectionAssistanceEntity parent = fixture.candidate("原始", "候选");
        parent.setChapterId(3L);
        when(fixture.chapterMapper.selectById(2L)).thenReturn(chapter);
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(parent);
        CreateRequest request = new CreateRequest(3, hash("原始正文"), 0, 2, "原始", "rewrite",
                "继续", 9L, "cross-parent");

        assertThatThrownBy(() -> fixture.service.create(2L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前章节");

        parent.setChapterId(2L);
        parent.setWorkId(99L);
        assertThatThrownBy(() -> fixture.service.create(2L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前章节");
    }

    @Test
    void continuesFromReadyParentWithoutOverwritingIt() {
        Fixture fixture = new Fixture();
        ChapterEntity chapter = fixture.chapter("co_creation", "开头原文结尾");
        ChapterSelectionAssistanceEntity parent = fixture.candidate("原文", "第一版候选");
        AtomicReference<ChapterSelectionAssistanceEntity> inserted = new AtomicReference<>();
        when(fixture.chapterMapper.selectById(2L)).thenReturn(chapter);
        when(fixture.assistanceMapper.selectById(any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return Long.valueOf(9L).equals(id) ? parent : inserted.get();
        });
        when(fixture.briefService.preview(2L, null)).thenReturn(new GenerationBriefPreview(
                1L, 2L, 3L, 1, "brief-v1", "current", List.of(), "b".repeat(64), null, "生成说明"));
        doAnswer(invocation -> {
            ((AiTaskEntity) invocation.getArgument(0)).setId(8L);
            return 1;
        }).when(fixture.taskMapper).insert(any(AiTaskEntity.class));
        doAnswer(invocation -> {
            ChapterSelectionAssistanceEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            inserted.set(entity);
            return 1;
        }).when(fixture.assistanceMapper).insert(any(ChapterSelectionAssistanceEntity.class));
        when(fixture.assistanceMapper.update(any(), any())).thenReturn(1);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.start(any())).thenReturn(fixture.runView("queued"));
        fixture.service.setAgentRuntime(runtime);

        fixture.service.continueFrom(9L, new SelectionAssistanceModels.ContinueRequest("再压缩", "child-1"));

        ArgumentCaptor<ChapterSelectionAssistanceEntity> captor =
                ArgumentCaptor.forClass(ChapterSelectionAssistanceEntity.class);
        verify(fixture.assistanceMapper).insert(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getParentId()).isEqualTo(9L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getRequestContractVersion()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCreatedCandidateId()).isNull();
        org.assertj.core.api.Assertions.assertThat(parent.getResultContent()).isEqualTo("第一版候选");
    }

    @Test
    void rejectsReadyCandidateExplicitly() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity candidate = fixture.candidate("原文", "候选");
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(candidate);
        when(fixture.assistanceMapper.update(any(), any())).thenReturn(1);

        fixture.service.reject(9L);

        verify(fixture.assistanceMapper).update(any(), any());
    }

    @Test
    void cancelsRunningRequestAndMarksPersistentState() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity candidate = fixture.candidate("原文", "候选");
        candidate.setRequestStatus("running");
        candidate.setAgentRunId(7L);
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(candidate);
        when(fixture.assistanceMapper.update(any(), any())).thenReturn(1);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.cancel(7L)).thenReturn(fixture.runView("canceled"));
        fixture.service.setAgentRuntime(runtime);

        fixture.service.cancel(9L);

        verify(runtime).cancel(7L);
        ArgumentCaptor<UpdateWrapper<ChapterSelectionAssistanceEntity>> update = updateCaptor();
        verify(fixture.assistanceMapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("proposal_status");
        assertThat(update.getValue().getParamNameValuePairs()).containsValue("canceled");
    }

    @Test
    void failedRequestMarksProposalFailed() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity candidate = fixture.candidate("原文", "候选");
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(candidate);
        when(fixture.assistanceMapper.update(any(), any())).thenReturn(1);

        fixture.service.fail(9L, "MODEL_FAILED");

        ArgumentCaptor<UpdateWrapper<ChapterSelectionAssistanceEntity>> update = updateCaptor();
        verify(fixture.assistanceMapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("proposal_status");
        assertThat(update.getValue().getParamNameValuePairs()).containsValue("failed");
    }

    @Test
    void retriesFailedModificationWithConsistentProposalStates() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity failed = fixture.candidate("原文", "候选");
        failed.setRequestStatus("failed");
        failed.setProposalStatus("failed");
        ChapterSelectionAssistanceEntity running = fixture.candidate("原文", "候选");
        running.setRequestStatus("running");
        running.setProposalStatus("pending");
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(failed, running);
        when(fixture.assistanceMapper.update(any(), any())).thenReturn(1);

        fixture.service.markRunning(9L);
        fixture.service.complete(9L, "重试后的候选", "safe", List.of(), null, "model-call-retry");

        ArgumentCaptor<UpdateWrapper<ChapterSelectionAssistanceEntity>> updates = updateCaptor();
        verify(fixture.assistanceMapper, org.mockito.Mockito.times(2))
                .update(org.mockito.ArgumentMatchers.isNull(), updates.capture());
        assertThat(updates.getAllValues().get(0).getParamNameValuePairs())
                .containsValue("running")
                .containsValue("pending");
        assertThat(updates.getAllValues().get(1).getParamNameValuePairs())
                .containsValue("ready");
        verify(fixture.planningChangeService, never()).createCandidate(any(), any());
    }

    @Test
    void planningProposalAlwaysRequiresAuthorReviewAndRollsIntoSameCompletionTransaction() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity running = fixture.candidate("原文", "候选");
        running.setRequestStatus("running");
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(running);
        when(fixture.assistanceMapper.update(any(), any())).thenReturn(1);
        ModelPlanningProposal proposal = new ModelPlanningProposal(
                "需要同步场景结果", "变更前", "变更后", List.of());

        fixture.service.complete(9L, "新候选", "safe", List.of(), proposal, "model-call-planning");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                fixture.planningChangeService, fixture.assistanceMapper);
        order.verify(fixture.planningChangeService).createCandidate(9L, proposal);
        ArgumentCaptor<UpdateWrapper<ChapterSelectionAssistanceEntity>> update = updateCaptor();
        order.verify(fixture.assistanceMapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        assertThat(update.getValue().getParamNameValuePairs())
                .containsValue("review_required")
                .containsValue("ready");
    }

    @Test
    void restoresDiscussionProposalStateWhenFailedRequestRunsAgain() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity failed = fixture.candidate("原文", "候选");
        failed.setOperationType("discuss");
        failed.setRequestStatus("failed");
        failed.setProposalStatus("failed");
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(failed);
        when(fixture.assistanceMapper.update(any(), any())).thenReturn(1);

        fixture.service.markRunning(9L);

        ArgumentCaptor<UpdateWrapper<ChapterSelectionAssistanceEntity>> update = updateCaptor();
        verify(fixture.assistanceMapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        assertThat(update.getValue().getParamNameValuePairs())
                .containsValue("running")
                .containsValue("discussion");
    }

    @Test
    void retriesFailedRequestWithExpectedAttempt() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity candidate = fixture.candidate("原文", "候选");
        candidate.setRequestStatus("failed");
        candidate.setAgentRunId(7L);
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(candidate);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.retryStep(any())).thenReturn(fixture.runView("queued"));
        fixture.service.setAgentRuntime(runtime);

        fixture.service.retry(9L, new SelectionAssistanceModels.RetryRequest(2));

        verify(runtime).retryStep(new RetryAgentStepCommand(
                7L, SelectionAssistanceServiceImpl.GENERATE_STEP, 2));
    }

    @Test
    void exposesStablePublicFailureOnlyForFailedSelectionRequest() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity failed = fixture.candidate("原文", "候选");
        failed.setRequestStatus("failed");
        failed.setErrorCode("TASK_QUEUE_FULL");
        failed.setErrorMessage("chapterId must not be null");
        AiTaskEntity task = new AiTaskEntity();
        task.setDiagnosticRef("diag_selection_ref");
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(failed);
        when(fixture.taskMapper.selectById(8L)).thenReturn(task);

        var failedView = fixture.service.get(9L);
        failed.setErrorCode(null);
        failed.setErrorMessage(null);
        var successView = fixture.service.get(9L);

        assertThat(failedView.failure().diagnosticRef()).isEqualTo("diag_selection_ref");
        assertThat(failedView.failure().category()).isEqualTo("service_unavailable");
        assertThat(failedView.errorMessage()).isEqualTo("依赖服务暂时不可用");
        assertThat(successView.failure()).isNull();
        assertThat(successView.errorMessage()).isNull();
    }

    @Test
    void acceptsCandidateOnlyWhenVersionHashAndSelectedTextStillMatch() {
        Fixture fixture = new Fixture();
        ChapterEntity chapter = fixture.chapter("co_creation", "开头原文结尾");
        ChapterSelectionAssistanceEntity candidate = fixture.candidate("原文", "新文");
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(candidate);
        when(fixture.chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter);
        when(fixture.chapterMapper.updateContentIfVersion(2L, "开头新文结尾", 3)).thenReturn(1);
        when(fixture.assistanceMapper.update(any(), any())).thenReturn(1);

        fixture.service.accept(9L, new AcceptRequest(3, hash("开头原文结尾")));

        verify(fixture.chapterMapper).updateContentIfVersion(2L, "开头新文结尾", 3);
        ArgumentCaptor<UpdateWrapper<ChapterSelectionAssistanceEntity>> update = updateCaptor();
        verify(fixture.assistanceMapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("proposal_status");
        assertThat(update.getValue().getParamNameValuePairs()).containsValue("accepted");
    }

    @Test
    void refusesCandidateWhenSelectionDriftedDespiteClientClaim() {
        Fixture fixture = new Fixture();
        ChapterEntity chapter = fixture.chapter("co_creation", "开头变化结尾");
        ChapterSelectionAssistanceEntity candidate = fixture.candidate("原文", "新文");
        candidate.setBaseContentHash(hash("开头变化结尾"));
        when(fixture.assistanceMapper.selectById(9L)).thenReturn(candidate);
        when(fixture.chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter);

        assertThatThrownBy(() -> fixture.service.accept(9L, new AcceptRequest(3, hash("开头变化结尾"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("选区原文已变化");
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ArgumentCaptor<UpdateWrapper<ChapterSelectionAssistanceEntity>> updateCaptor() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ChapterSelectionAssistanceEntity>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        return captor;
    }

    private static final class Fixture {
        private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
        private final ChapterSelectionAssistanceMapper assistanceMapper = mock(ChapterSelectionAssistanceMapper.class);
        private final AiTaskMapper taskMapper = mock(AiTaskMapper.class);
        private final ChapterGenerationBriefService briefService = mock(ChapterGenerationBriefService.class);
        private final ChapterProseCandidateMapper candidateMapper = mock(ChapterProseCandidateMapper.class);
        private final ChapterGenerationMapper generationMapper = mock(ChapterGenerationMapper.class);
        private final ChapterConversationMapper conversationMapper = mock(ChapterConversationMapper.class);
        private final ChapterConversationMessageMapper messageMapper = mock(ChapterConversationMessageMapper.class);
        private final ProsePlanningChangeService planningChangeService = mock(ProsePlanningChangeService.class);
        private final SelectionAssistanceServiceImpl service = new SelectionAssistanceServiceImpl(chapterMapper,
                assistanceMapper, taskMapper, briefService, new ObjectMapper());

        private Fixture() {
            service.setWorkspaceDependencies(candidateMapper, generationMapper, conversationMapper,
                    messageMapper, planningChangeService);
        }

        private ChapterEntity chapter(String workflowStatus, String content) {
            ChapterEntity chapter = new ChapterEntity();
            chapter.setId(2L);
            chapter.setWorkId(1L);
            chapter.setContent(content);
            chapter.setWorkflowStatus(workflowStatus);
            chapter.setVersion(3);
            chapter.setDeleted(0);
            return chapter;
        }

        private ChapterSelectionAssistanceEntity candidate(String selected, String replacement) {
            ChapterSelectionAssistanceEntity entity = new ChapterSelectionAssistanceEntity();
            entity.setId(9L);
            entity.setWorkId(1L);
            entity.setChapterId(2L);
            entity.setAiTaskId(8L);
            entity.setOperationType("rewrite");
            entity.setRequestStatus("ready");
            entity.setBaseChapterVersion(3);
            entity.setBaseContentHash(hash("开头原文结尾"));
            entity.setSelectionStart(2);
            entity.setSelectionEnd(4);
            entity.setSelectedText(selected);
            entity.setResultContent(replacement);
            entity.setFactRiskReasonsJson("[]");
            entity.setVersion(1);
            entity.setDeleted(0);
            return entity;
        }

        private ChapterSelectionAssistanceEntity idempotentRecord(String operation, String key) {
            ChapterSelectionAssistanceEntity entity = candidate("原始", "候选");
            entity.setOperationType(operation);
            entity.setBaseContentHash(hash("原始正文"));
            entity.setSelectionStart(0);
            entity.setSelectionEnd(2);
            entity.setUserInstruction("保持事实");
            entity.setIdempotencyKey(key);
            return entity;
        }

        private AgentRunView runView(String status) {
            return new AgentRunView(7L, SelectionAssistanceServiceImpl.WORKFLOW_TYPE, status,
                    1L, 2L, 8L, SelectionAssistanceServiceImpl.GENERATE_STEP,
                    null, null, null, null, null, null);
        }
    }
}
