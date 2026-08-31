package com.dugnan.moqi.chapter.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefPreview;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.CreateRequest;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.service.ProseObjectConversationService;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConversationDetail;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 验证新版正文目标协助创建候选、持久化提案和共享讨论引用。
 */
class SelectionAssistanceTargetServiceTest {

    @Test
    void formalRewriteCreatesStableCandidateWithoutUpdatingFormalProse() {
        Fixture fixture = new Fixture();

        var view = fixture.service.create(2L, fixture.request(
                "formal", "formal:2", 3, "rewrite", "formal-rewrite"));

        assertThat(view.targetKind()).isEqualTo("candidate");
        assertThat(view.createdCandidateId()).isEqualTo(40L);
        assertThat(view.canAccept()).isFalse();
        verify(fixture.generationMapper).insert(argThat((ChapterGenerationEntity generation) ->
                generation.getBaseGenerationId().equals(49L)
                        && "{\"discussionBasis\":\"confirmed\"}".equals(generation.getBasisSnapshotJson())));
        verify(fixture.candidateMapper).insert(any(ChapterProseCandidateEntity.class));
        verify(fixture.chapterMapper, never()).updateContentIfVersion(any(), any(), any());
    }

    @Test
    void candidateRewritePersistsProposalWithoutWritingCandidate() {
        Fixture fixture = new Fixture();
        fixture.candidate.setId(41L);
        fixture.candidate.setVersion(5);
        fixture.candidate.setContentHash(hash(fixture.chapter.getContent()));
        when(fixture.candidateMapper.selectOne(any())).thenReturn(fixture.candidate);

        var view = fixture.service.create(2L, fixture.request(
                "candidate", "candidate:41", 5, "rewrite", "candidate-rewrite"));

        assertThat(view.targetId()).isEqualTo("candidate:41");
        assertThat(view.proposalStatus()).isEqualTo("pending");
        verify(fixture.candidateMapper, never()).insert(any(ChapterProseCandidateEntity.class));
        verify(fixture.candidateMapper, never()).updateContentIfVersion(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void discussionUsesTargetObjectConversationAndVersionedReference() {
        Fixture fixture = new Fixture();

        var view = fixture.service.create(2L, fixture.request(
                "formal", "formal:2", 3, "discuss", "shared-discussion"));

        assertThat(view.conversationId()).isEqualTo(20L);
        assertThat(view.userMessageId()).isEqualTo(30L);
        assertThat(view.referenceTextHash()).isEqualTo(hash("原始"));
        assertThat(view.referenceSentenceCount()).isEqualTo(1);
        assertThat(view.referenceStale()).isFalse();
        verify(fixture.proseObjectConversationService).createOrGet(2L, "formal:2");
        verify(fixture.conversationMapper, never()).insert(any(ChapterConversationEntity.class));
        verify(fixture.messageMapper).insert(any(ChapterConversationMessageEntity.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"discuss", "rewrite", "polish", "expand", "compress"})
    void everyReferencedOperationBelongsToTheTargetObjectConversation(String operation) {
        Fixture fixture = new Fixture();
        fixture.candidate.setId(41L);
        fixture.candidate.setVersion(5);
        fixture.candidate.setContentHash(hash(fixture.chapter.getContent()));

        var view = fixture.service.create(2L, fixture.request(
                "candidate", "candidate:41", 5, operation, "conversation-" + operation));

        assertThat(view.conversationId()).isEqualTo(20L);
        assertThat(view.userMessageId()).isEqualTo(30L);
        verify(fixture.proseObjectConversationService).createOrGet(2L, "candidate:41");
        verify(fixture.messageMapper).insert(argThat((ChapterConversationMessageEntity message) ->
                "user".equals(message.getMessageRole())
                        && message.getContent().contains("作者要求：保持事实")
                        && message.getContent().contains("引用正文：\n原始")
                        && message.getClientMessageId() != null));
    }

    @Test
    void freezesExistingObjectConversationHistoryBeforeTheCurrentReferenceMessage() {
        Fixture fixture = new Fixture();
        ChapterConversationMessageEntity previousUser = fixture.message(11L, "user", "上一轮作者要求");
        ChapterConversationMessageEntity previousAssistant = fixture.message(12L, "assistant", "上一轮候选建议");
        when(fixture.messageMapper.selectList(any())).thenReturn(List.of(previousUser, previousAssistant));

        fixture.service.create(2L, fixture.request(
                "formal", "formal:2", 3, "discuss", "history-discussion"));
        when(fixture.messageMapper.selectList(any())).thenReturn(List.of(
                fixture.message(13L, "user", "创建之后的新消息")));

        assertThat(fixture.service.modelHistory(9L))
                .containsExactly(
                        new SelectionAssistanceModels.ConversationHistoryMessage("user", "上一轮作者要求"),
                        new SelectionAssistanceModels.ConversationHistoryMessage("assistant", "上一轮候选建议"));
    }

    @Test
    void modificationResultIsPersistedAsAnUnappliedAssistantProposal() {
        Fixture fixture = new Fixture();
        fixture.candidate.setId(41L);
        fixture.candidate.setVersion(5);
        fixture.candidate.setContentHash(hash(fixture.chapter.getContent()));
        fixture.service.create(2L, fixture.request(
                "candidate", "candidate:41", 5, "polish", "persist-polish"));

        fixture.service.complete(9L, "润色后的正文", "safe", List.of(), null, "model-call");

        verify(fixture.messageMapper).insert(argThat((ChapterConversationMessageEntity message) ->
                "assistant".equals(message.getMessageRole())
                        && message.getContent().contains("已生成润色提案")
                        && message.getContent().contains("尚未应用或保存")
                        && message.getContent().contains("润色后的正文")
                        && message.getClientMessageId() != null));
    }

    @Test
    void formalReplayAfterConcurrentCommitReusesCandidateAndModelTask() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity existing = fixture.persistedFormalRewrite();
        when(fixture.assistanceMapper.selectOne(any())).thenReturn(null, existing);

        var view = fixture.service.create(2L, fixture.request(
                "formal", "formal:2", 3, "rewrite", "formal-concurrent"));

        assertThat(view.createdCandidateId()).isEqualTo(40L);
        verify(fixture.chapterMapper).selectByIdForUpdate(2L);
        verify(fixture.generationMapper, never()).insert(any(ChapterGenerationEntity.class));
        verify(fixture.candidateMapper, never()).insert(any(ChapterProseCandidateEntity.class));
        verify(fixture.taskMapper, never()).insert(any(AiTaskEntity.class));
    }

    @Test
    void candidateReplayAfterConcurrentCommitReusesProposalWithoutDuplicateTask() {
        Fixture fixture = new Fixture();
        fixture.candidate.setId(41L);
        fixture.candidate.setVersion(5);
        fixture.candidate.setContentHash(hash(fixture.chapter.getContent()));
        ChapterSelectionAssistanceEntity existing = fixture.persistedCandidateRewrite();
        when(fixture.assistanceMapper.selectOne(any())).thenReturn(null, existing);

        var view = fixture.service.create(2L, fixture.request(
                "candidate", "candidate:41", 5, "rewrite", "candidate-concurrent"));

        assertThat(view.targetId()).isEqualTo("candidate:41");
        verify(fixture.chapterMapper).selectByIdForUpdate(2L);
        verify(fixture.candidateMapper).selectByIdForUpdate(2L, 41L);
        verify(fixture.taskMapper, never()).insert(any(AiTaskEntity.class));
        verify(fixture.assistanceMapper, never()).insert(any(ChapterSelectionAssistanceEntity.class));
    }

    @Test
    void formalDiscussionReplayAfterConcurrentCommitDoesNotDuplicateObjectMessage() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity existing = fixture.persistedFormalRewrite();
        existing.setOperationType("discuss");
        existing.setCreatedCandidateId(null);
        existing.setTargetKind("formal");
        existing.setTargetObjectId("formal:2");
        existing.setTargetCandidateId(null);
        existing.setTargetContentVersion(3);
        existing.setProposalStatus("discussion");
        when(fixture.assistanceMapper.selectOne(any())).thenReturn(null, existing);

        var view = fixture.service.create(2L, fixture.request(
                "formal", "formal:2", 3, "discuss", "formal-discuss-concurrent"));

        assertThat(view.operation()).isEqualTo("discuss");
        verify(fixture.chapterMapper).selectByIdForUpdate(2L);
        verify(fixture.messageMapper, never()).insert(any(ChapterConversationMessageEntity.class));
        verify(fixture.taskMapper, never()).insert(any(AiTaskEntity.class));
    }

    @Test
    void formalSequentialReplayReusesCandidateAndModelTask() {
        Fixture fixture = new Fixture();
        CreateRequest request = fixture.request(
                "formal", "formal:2", 3, "rewrite", "k".repeat(128));

        var first = fixture.service.create(2L, request);
        when(fixture.assistanceMapper.selectOne(any())).thenReturn(fixture.assistance.get());
        var replay = fixture.service.create(2L, request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.createdCandidateId()).isEqualTo(first.createdCandidateId());
        verify(fixture.generationMapper, times(1)).insert(any(ChapterGenerationEntity.class));
        verify(fixture.generationMapper).insert(argThat((ChapterGenerationEntity generation) ->
                generation.getIdempotencyKey().startsWith("formal-assistance:")
                        && generation.getIdempotencyKey().length() <= 128));
        verify(fixture.candidateMapper, times(1)).insert(any(ChapterProseCandidateEntity.class));
        verify(fixture.taskMapper, times(1)).insert(any(AiTaskEntity.class));
        verify(fixture.messageMapper, times(1)).insert(any(ChapterConversationMessageEntity.class));
    }

    @Test
    void reloadsPersistedTargetAndPlanningPackageWithoutStartingRuntime() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity persisted = fixture.persistedFormalRewrite();
        fixture.assistance.set(persisted);
        when(fixture.planningChangeService.getByAssistance(9L)).thenReturn(
                new SelectionAssistanceModels.PlanningChangePackageView(
                        30L, "candidate:40", 0, "pending", "调整规划", "变更前", "变更后", 2, 3, 4,
                        List.of(), null, 0, null, null));

        var reloaded = fixture.service.get(9L);

        assertThat(reloaded.targetId()).isEqualTo("candidate:40");
        assertThat(reloaded.referenceStale()).isFalse();
        assertThat(reloaded.planningChangePackageId()).isEqualTo(30L);
        verifyNoInteractions(fixture.agentRuntime);
    }

    @Test
    void rejectsIdempotencyReplayAcrossFormalAndCandidateTargets() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity existing = fixture.persistedFormalRewrite();
        when(fixture.assistanceMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> fixture.service.create(2L, fixture.request(
                "candidate", "candidate:40", 0, "rewrite", "same-key")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("幂等键已经绑定不同");
    }

    @Test
    void rejectsIdempotencyReplayAcrossDifferentCandidateObjects() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity existing = fixture.persistedCandidateRewrite();
        when(fixture.assistanceMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> fixture.service.create(2L, fixture.request(
                "candidate", "candidate:42", 5, "rewrite", "same-key")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("幂等键已经绑定不同");
    }

    @Test
    void rejectsIdempotencyReplayAcrossWholeAndSelectionReferences() {
        Fixture fixture = new Fixture();
        ChapterSelectionAssistanceEntity existing = fixture.persistedCandidateRewrite();
        existing.setReferenceScope("whole");
        when(fixture.assistanceMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> fixture.service.create(2L, fixture.request(
                "candidate", "candidate:41", 5, "rewrite", "same-key")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("幂等键已经绑定不同");
    }

    @Test
    void rejectsPartialTargetContractBeforeCreatingAnyPersistentWork() {
        Fixture fixture = new Fixture();
        CreateRequest partial = new CreateRequest(
                3, hash(fixture.chapter.getContent()), 0, 2, "原始", "rewrite",
                "保持事实", null, "partial-target", null, "candidate:41", 5, null);

        assertThatThrownBy(() -> fixture.service.create(2L, partial))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("targetKind");

        verify(fixture.taskMapper, never()).insert(any(AiTaskEntity.class));
        verify(fixture.generationMapper, never()).insert(any(ChapterGenerationEntity.class));
        verify(fixture.candidateMapper, never()).insert(any(ChapterProseCandidateEntity.class));
        verify(fixture.messageMapper, never()).insert(any(ChapterConversationMessageEntity.class));
    }

    @Test
    void continuesVersionTwoParentOnSameStableCandidate() {
        Fixture fixture = new Fixture();
        fixture.candidate.setId(41L);
        fixture.candidate.setVersion(5);
        fixture.candidate.setContentHash(hash(fixture.chapter.getContent()));
        ChapterSelectionAssistanceEntity parent = fixture.persistedCandidateRewrite();
        parent.setRequestStatus("ready");
        fixture.assistance.set(parent);

        var continued = fixture.service.continueFrom(
                9L, new SelectionAssistanceModels.ContinueRequest("继续凝练", "candidate-child"));

        assertThat(continued.targetKind()).isEqualTo("candidate");
        assertThat(continued.targetId()).isEqualTo("candidate:41");
        assertThat(fixture.assistance.get().getRequestContractVersion()).isEqualTo(2);
        assertThat(fixture.assistance.get().getParentId()).isEqualTo(9L);
        verify(fixture.candidateMapper).selectByIdForUpdate(2L, 41L);
        verify(fixture.candidateMapper, never()).updateContentIfVersion(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingLockedCandidateUsesStableNotFoundCodeWithoutPersistentWrites() {
        Fixture fixture = new Fixture();
        when(fixture.candidateMapper.selectByIdForUpdate(2L, 404L)).thenReturn(null);

        assertThatThrownBy(() -> fixture.service.create(2L, fixture.request(
                "candidate", "candidate:404", 0, "rewrite", "missing-candidate")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PROSE_CANDIDATE_NOT_FOUND));

        verify(fixture.taskMapper, never()).insert(any(AiTaskEntity.class));
        verify(fixture.assistanceMapper, never()).insert(any(ChapterSelectionAssistanceEntity.class));
        verify(fixture.messageMapper, never()).insert(any(ChapterConversationMessageEntity.class));
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
        private final ProseObjectConversationService proseObjectConversationService = mock(ProseObjectConversationService.class);
        private final AgentRuntime agentRuntime = mock(AgentRuntime.class);
        private final ChapterEntity chapter = chapter();
        private final ChapterProseCandidateEntity candidate = candidate();
        private final AtomicReference<ChapterSelectionAssistanceEntity> assistance = new AtomicReference<>();
        private final AtomicReference<String> conversationTarget = new AtomicReference<>("formal:2");
        private final SelectionAssistanceServiceImpl service = new SelectionAssistanceServiceImpl(
                chapterMapper, assistanceMapper, taskMapper, briefService, new ObjectMapper());

        private Fixture() {
            service.setAgentRuntime(agentRuntime);
            service.setWorkspaceDependencies(candidateMapper, generationMapper, conversationMapper,
                    messageMapper, planningChangeService, proseObjectConversationService);
            when(chapterMapper.selectById(2L)).thenReturn(chapter);
            when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter);
            ChapterGenerationEntity formalSource = new ChapterGenerationEntity();
            formalSource.setId(49L);
            formalSource.setWorkId(1L);
            formalSource.setChapterId(2L);
            formalSource.setBasisSnapshotJson("{\"discussionBasis\":\"confirmed\"}");
            formalSource.setDeleted(0);
            when(generationMapper.selectById(49L)).thenReturn(formalSource);
            when(briefService.preview(2L, null)).thenReturn(new GenerationBriefPreview(
                    1L, 2L, 3L, 1, "brief-v1", "current", List.of(), "b".repeat(64), null, "生成说明"));
            doAnswer(invocation -> {
                invocation.getArgument(0, AiTaskEntity.class).setId(8L);
                return 1;
            }).when(taskMapper).insert(any(AiTaskEntity.class));
            doAnswer(invocation -> {
                invocation.getArgument(0, ChapterGenerationEntity.class).setId(50L);
                return 1;
            }).when(generationMapper).insert(any(ChapterGenerationEntity.class));
            doAnswer(invocation -> {
                ChapterProseCandidateEntity inserted = invocation.getArgument(0);
                inserted.setId(40L);
                candidate.setId(40L);
                candidate.setVersion(inserted.getVersion());
                candidate.setContent(inserted.getContent());
                candidate.setContentHash(inserted.getContentHash());
                candidate.setDeleted(0);
                return 1;
            }).when(candidateMapper).insert(any(ChapterProseCandidateEntity.class));
            when(candidateMapper.update(any(), any())).thenReturn(1);
            when(candidateMapper.selectById(any())).thenReturn(candidate);
            when(candidateMapper.selectByIdForUpdate(any(), any())).thenReturn(candidate);
            doAnswer(invocation -> {
                ChapterConversationEntity inserted = invocation.getArgument(0);
                inserted.setId(20L);
                return 1;
            }).when(conversationMapper).insert(any(ChapterConversationEntity.class));
            when(conversationMapper.selectList(any())).thenReturn(List.of());
            when(proseObjectConversationService.createOrGet(any(), any())).thenAnswer(invocation -> {
                String targetObjectId = invocation.getArgument(1);
                conversationTarget.set(targetObjectId);
                return new ConversationDetail(
                        20L, 1L, 2L, "prose_object", "active", null, null, targetObjectId);
            });
            when(conversationMapper.selectById(20L)).thenAnswer(invocation -> {
                ChapterConversationEntity objectConversation = new ChapterConversationEntity();
                objectConversation.setId(20L);
                objectConversation.setChapterId(2L);
                objectConversation.setTargetObjectId(conversationTarget.get());
                return objectConversation;
            });
            doAnswer(invocation -> {
                ChapterConversationMessageEntity inserted = invocation.getArgument(0);
                inserted.setId(30L);
                return 1;
            }).when(messageMapper).insert(any(ChapterConversationMessageEntity.class));
            doAnswer(invocation -> {
                ChapterSelectionAssistanceEntity inserted = invocation.getArgument(0);
                inserted.setId(9L);
                assistance.set(inserted);
                return 1;
            }).when(assistanceMapper).insert(any(ChapterSelectionAssistanceEntity.class));
            when(assistanceMapper.selectById(9L)).thenAnswer(invocation -> assistance.get());
            when(assistanceMapper.update(any(), any())).thenReturn(1);
            when(agentRuntime.start(any())).thenReturn(new AgentRunView(
                    7L, SelectionAssistanceServiceImpl.WORKFLOW_TYPE, "queued", 1L, 2L, 8L,
                    SelectionAssistanceServiceImpl.GENERATE_STEP, null, null, null, null, null, null));
        }

        private CreateRequest request(
                String targetKind,
                String targetId,
                Integer targetVersion,
                String operation,
                String idempotencyKey) {
            return new CreateRequest(null, hash(chapter.getContent()), 0, 2, "原始", operation,
                    "保持事实", null, idempotencyKey, targetKind, targetId, targetVersion, "selection");
        }

        private ChapterSelectionAssistanceEntity persistedFormalRewrite() {
            ChapterSelectionAssistanceEntity entity = new ChapterSelectionAssistanceEntity();
            entity.setId(9L);
            entity.setWorkId(1L);
            entity.setChapterId(2L);
            entity.setAiTaskId(8L);
            entity.setOperationType("rewrite");
            entity.setRequestStatus("queued");
            entity.setRequestContractVersion(2);
            entity.setTargetKind("candidate");
            entity.setTargetObjectId("candidate:40");
            entity.setTargetCandidateId(40L);
            entity.setTargetContentVersion(0);
            entity.setTargetContentHash(hash(chapter.getContent()));
            entity.setReferenceScope("selection");
            entity.setBaseChapterVersion(3);
            entity.setBaseContentHash(hash(chapter.getContent()));
            entity.setSelectionStart(0);
            entity.setSelectionEnd(2);
            entity.setSelectedText("原始");
            entity.setUserInstruction("保持事实");
            entity.setCreatedCandidateId(40L);
            entity.setProposalStatus("pending");
            entity.setFactRiskReasonsJson("[]");
            entity.setDeleted(0);
            entity.setVersion(1);
            return entity;
        }

        private ChapterSelectionAssistanceEntity persistedCandidateRewrite() {
            ChapterSelectionAssistanceEntity entity = persistedFormalRewrite();
            entity.setTargetObjectId("candidate:41");
            entity.setTargetCandidateId(41L);
            entity.setTargetContentVersion(5);
            entity.setCreatedCandidateId(null);
            return entity;
        }

        private ChapterConversationMessageEntity message(Long id, String role, String content) {
            ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
            message.setId(id);
            message.setConversationId(20L);
            message.setChapterId(2L);
            message.setMessageRole(role);
            message.setContent(content);
            message.setDeleted(0);
            return message;
        }

        private static ChapterEntity chapter() {
            ChapterEntity chapter = new ChapterEntity();
            chapter.setId(2L);
            chapter.setWorkId(1L);
            chapter.setContent("原始正文");
            chapter.setWorkflowStatus("done");
            chapter.setVersion(3);
            chapter.setFormalSourceGenerationId(49L);
            chapter.setDeleted(0);
            return chapter;
        }

        private static ChapterProseCandidateEntity candidate() {
            ChapterProseCandidateEntity candidate = new ChapterProseCandidateEntity();
            candidate.setChapterId(2L);
            candidate.setWorkId(1L);
            candidate.setContent("原始正文");
            candidate.setContentHash(hash("原始正文"));
            candidate.setVersion(0);
            candidate.setDeleted(0);
            return candidate;
        }
    }
}
