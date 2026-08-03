package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusImpactService;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.CreateOutlineCandidateRequest;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.UpdateOutlineCandidateRequest;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterOutlineCandidateEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterOutlineCandidateMapper;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Scene;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContentCodec;
import com.dugnan.moqi.chapter.outline.OutlineCandidateDiffService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 验证大纲调整候选的创建、幂等确认与条件更新约束。
 */
@ExtendWith(MockitoExtension.class)
class OutlineCandidateServiceImplTest {

    @Mock
    private WorkMapper workMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private ChapterConversationMapper conversationMapper;
    @Mock
    private ChapterBriefMapper briefMapper;
    @Mock
    private ChapterOutlineQueryMapper outlineMapper;
    @Mock
    private ChapterOutlineCandidateMapper candidateMapper;
    @Mock
    private AiTaskMapper taskMapper;
    @Mock
    private ChapterConsensusImpactService impactService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OutlineCandidateContentCodec contentCodec;
    private OutlineCandidateServiceImpl service;

    /**
     * 初始化候选服务。
     */
    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        contentCodec = new OutlineCandidateContentCodec(objectMapper);
        service = new OutlineCandidateServiceImpl(
                workMapper, chapterMapper, conversationMapper, briefMapper, outlineMapper, candidateMapper,
                taskMapper, contentCodec, impactService, new OutlineCandidateDiffService(), objectMapper, eventPublisher);
    }

    /**
     * 验证创建只保存任务与候选快照，不更新正式大纲 revision。
     */
    @Test
    void createsTaskAndCandidateWithoutUpdatingFormalOutline() {
        prepareCreateDependencies();
        when(taskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiTaskEntity.class).setId(8L);
            return 1;
        });
        when(candidateMapper.insert(any(ChapterOutlineCandidateEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChapterOutlineCandidateEntity.class).setId(7L);
            return 1;
        });

        var result = service.create(2L, new CreateOutlineCandidateRequest(3L, 4L, 5, "强化冲突"));

        assertThat(result.candidateId()).isEqualTo(7L);
        assertThat(result.aiTaskId()).isEqualTo(8L);
        assertThat(result.baseOutlineRevision()).isEqualTo(5);
        verify(outlineMapper, never()).updateByRevisionAndVersion(any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<ChapterOutlineCandidateEntity> candidateCaptor =
                ArgumentCaptor.forClass(ChapterOutlineCandidateEntity.class);
        verify(candidateMapper).insert(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().getBaseOutlineContent()).isEqualTo(contentJson("基础目标"));
        verify(taskMapper).updateById(any(AiTaskEntity.class));
    }

    /**
     * 验证会话不属于章节时拒绝创建。
     */
    @Test
    void rejectsConversationOutsideChapter() {
        when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        ChapterConversationEntity conversation = conversation();
        conversation.setChapterId(99L);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);

        assertThatThrownBy(() -> service.create(2L, new CreateOutlineCandidateRequest(3L, 4L, 5, "强化冲突")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
        verify(taskMapper, never()).insert(any(AiTaskEntity.class));
    }

    /**
     * 验证无正式大纲时可从已确认 Brief 创建首版候选，并持久化版本化幂等快照。
     */
    @Test
    void createsInitialCandidateWithoutFormalOutline() {
        when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        when(conversationMapper.selectById(3L)).thenReturn(conversation());
        when(briefMapper.findByIdAndChapterId(4L, 2L)).thenReturn(brief());
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed")).thenReturn(brief());
        when(taskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiTaskEntity.class).setId(8L);
            return 1;
        });
        when(candidateMapper.insert(any(ChapterOutlineCandidateEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChapterOutlineCandidateEntity.class).setId(7L);
            return 1;
        });

        var result = service.create(2L, new CreateOutlineCandidateRequest(
                3L, 4L, null, null, "initial", "initial-request-1"));

        assertThat(result.outlineId()).isNull();
        assertThat(result.candidateType()).isEqualTo("initial");
        ArgumentCaptor<AiTaskEntity> taskCaptor = ArgumentCaptor.forClass(AiTaskEntity.class);
        verify(taskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskInputJson())
                .contains("\"schemaVersion\":2", "\"candidateType\":\"initial\"", "\"idempotencyKey\":\"initial-request-1\"");
        ArgumentCaptor<ChapterOutlineCandidateEntity> candidateCaptor =
                ArgumentCaptor.forClass(ChapterOutlineCandidateEntity.class);
        verify(candidateMapper).insert(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().getBaseOutlineId()).isNull();
        assertThat(candidateCaptor.getValue().getAdjustmentInstruction()).isNotBlank();
    }

    /**
     * 验证同一首版幂等键返回既有候选和任务，不创建重复记录。
     */
    @Test
    void reusesInitialCandidateForSameIdempotencyKey() {
        when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        when(conversationMapper.selectById(3L)).thenReturn(conversation());
        when(briefMapper.findByIdAndChapterId(4L, 2L)).thenReturn(brief());
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed")).thenReturn(brief());
        ChapterOutlineCandidateEntity existing = readyCandidate();
        existing.setCandidateType("initial");
        existing.setIdempotencyKey("initial-request-1");
        existing.setBaseOutlineId(null);
        existing.setBaseOutlineRevision(null);
        existing.setBaseOutlineContent(null);
        when(candidateMapper.findByIdempotencyKey(2L, "initial-request-1")).thenReturn(existing);
        AiTaskEntity task = new AiTaskEntity();
        task.setId(8L);
        task.setTaskStatus("succeeded");
        when(taskMapper.selectById(8L)).thenReturn(task);

        var result = service.create(2L, new CreateOutlineCandidateRequest(
                3L, 4L, null, null, "initial", "initial-request-1"));

        assertThat(result.candidateId()).isEqualTo(7L);
        assertThat(result.taskStatus()).isEqualTo("succeeded");
        verify(taskMapper, never()).insert(any(AiTaskEntity.class));
        verify(candidateMapper, never()).insert(any(ChapterOutlineCandidateEntity.class));
    }

    /**
     * 验证没有已确认 Brief 时不能创建首版候选。
     */
    @Test
    void rejectsInitialCandidateWithoutConfirmedBrief() {
        when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        when(conversationMapper.selectById(3L)).thenReturn(conversation());

        assertThatThrownBy(() -> service.create(2L, new CreateOutlineCandidateRequest(
                3L, 4L, null, null, "initial", "initial-request-1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_CONFIRMED_BRIEF_REQUIRED);
        verify(taskMapper, never()).insert(any(AiTaskEntity.class));
    }

    /**
     * 验证正式大纲已经存在时拒绝创建首版候选。
     */
    @Test
    void rejectsInitialCandidateWhenFormalOutlineExists() {
        when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        when(conversationMapper.selectById(3L)).thenReturn(conversation());
        when(briefMapper.findByIdAndChapterId(4L, 2L)).thenReturn(brief());
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed")).thenReturn(brief());
        when(outlineMapper.findLatest(2L)).thenReturn(outline(0, 0, contentJson("正式目标")));

        assertThatThrownBy(() -> service.create(2L, new CreateOutlineCandidateRequest(
                3L, 4L, null, null, "initial", "initial-request-1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OUTLINE_CANDIDATE_STATE_CONFLICT);
        verify(taskMapper, never()).insert(any(AiTaskEntity.class));
    }

    /**
     * 验证首版候选编辑执行结构校验和候选版本 CAS。
     */
    @Test
    void editsReadyInitialCandidateWithOptimisticLock() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        ChapterOutlineCandidateEntity candidate = readyCandidate();
        candidate.setCandidateType("initial");
        candidate.setBaseOutlineId(null);
        candidate.setBaseOutlineRevision(null);
        candidate.setBaseOutlineContent(null);
        when(candidateMapper.selectById(7L)).thenReturn(candidate);
        when(briefMapper.findByIdAndChapterId(4L, 2L)).thenReturn(brief());
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed")).thenReturn(brief());
        when(candidateMapper.update(eq(null), any())).thenReturn(1);

        var result = service.update(2L, 7L, new UpdateOutlineCandidateRequest(
                new OutlineCandidateContent("编辑后目标", "编辑后冲突",
                        List.of(new Scene("scene-1", "场景", "编辑后内容", List.of())), List.of("约束")),
                2));

        assertThat(result.candidateVersion()).isEqualTo(3);
        assertThat(result.candidateContent().goal()).isEqualTo("编辑后目标");
        assertThat(result.diff()).isNull();
    }

    /**
     * 验证首版候选确认创建唯一正式大纲并返回初始 revision。
     */
    @Test
    void confirmsInitialCandidateByCreatingFormalOutline() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        ChapterOutlineCandidateEntity candidate = readyCandidate();
        candidate.setCandidateType("initial");
        candidate.setBaseOutlineId(null);
        candidate.setBaseOutlineRevision(null);
        candidate.setBaseOutlineContent(null);
        when(candidateMapper.findByIdForUpdate(7L, 2L)).thenReturn(candidate);
        when(briefMapper.findByIdAndChapterId(4L, 2L)).thenReturn(brief());
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed")).thenReturn(brief());
        ChapterOutlineEntity confirmed = outline(0, 0, contentJson("候选目标"));
        when(outlineMapper.findLatest(2L)).thenReturn(null, confirmed);
        when(outlineMapper.insert(any(ChapterOutlineEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChapterOutlineEntity.class).setId(6L);
            return 1;
        });
        when(candidateMapper.update(eq(null), any())).thenReturn(1);

        var result = service.confirm(2L, 7L);

        assertThat(result.candidate().resultOutlineId()).isEqualTo(6L);
        assertThat(result.candidate().resultOutlineRevision()).isZero();
        assertThat(result.outline().confirmedBriefId()).isEqualTo(4L);
        verify(outlineMapper).insert(any(ChapterOutlineEntity.class));
        verify(outlineMapper, never()).updateByRevisionAndVersion(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * 验证并发请求已创建正式大纲时，首版确认安全返回冲突且不确认候选。
     */
    @Test
    void rejectsInitialConfirmationWhenConcurrentOutlineWins() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        ChapterOutlineCandidateEntity candidate = readyCandidate();
        candidate.setCandidateType("initial");
        candidate.setBaseOutlineId(null);
        candidate.setBaseOutlineRevision(null);
        candidate.setBaseOutlineContent(null);
        when(candidateMapper.findByIdForUpdate(7L, 2L)).thenReturn(candidate);
        when(briefMapper.findByIdAndChapterId(4L, 2L)).thenReturn(brief());
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed")).thenReturn(brief());
        when(outlineMapper.insert(any(ChapterOutlineEntity.class)))
                .thenThrow(new DuplicateKeyException("chapter outline exists"));

        assertThatThrownBy(() -> service.confirm(2L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OUTLINE_CANDIDATE_STALE);
        verify(candidateMapper, never()).update(eq(null), any());
    }

    /**
     * 验证就绪候选确认只执行一次正式大纲 CAS，重复确认不重复写入。
     */
    @Test
    void confirmsOnceAndKeepsRepeatedConfirmationIdempotent() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        ChapterOutlineCandidateEntity candidate = readyCandidate();
        when(candidateMapper.findByIdForUpdate(7L, 2L)).thenReturn(candidate);
        when(briefMapper.findByIdAndChapterId(4L, 2L)).thenReturn(brief());
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed")).thenReturn(brief());
        ChapterOutlineEntity before = outline(5, 3, contentJson("基础目标"));
        ChapterOutlineEntity after = outline(6, 4, contentJson("候选目标"));
        when(outlineMapper.findLatest(2L)).thenReturn(before, after);
        when(outlineMapper.updateByRevisionAndVersion(eq(6L), eq(2L), eq(4L), eq("draft"), any(), eq(5), eq(3)))
                .thenReturn(1);
        when(candidateMapper.update(eq(null), any())).thenReturn(1);

        var first = service.confirm(2L, 7L);
        assertThat(first.candidate().candidateStatus()).isEqualTo("confirmed");
        assertThat(first.outline().revision()).isEqualTo(6);

        var second = service.confirm(2L, 7L);
        assertThat(second.candidate().resultOutlineRevision()).isEqualTo(6);
        verify(outlineMapper).updateByRevisionAndVersion(eq(6L), eq(2L), eq(4L), eq("draft"), any(), eq(5), eq(3));
    }

    /**
     * 验证候选基础 revision 已失效时不执行正式大纲写入。
     */
    @Test
    void rejectsStaleCandidateBeforeFormalOutlineWrite() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        when(candidateMapper.findByIdForUpdate(7L, 2L)).thenReturn(readyCandidate());
        when(briefMapper.findByIdAndChapterId(4L, 2L)).thenReturn(brief());
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed")).thenReturn(brief());
        when(outlineMapper.findLatest(2L)).thenReturn(outline(6, 3, contentJson("基础目标")));

        assertThatThrownBy(() -> service.confirm(2L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OUTLINE_CANDIDATE_STALE);
        verify(outlineMapper, never()).updateByRevisionAndVersion(any(), any(), any(), any(), any(), any(), any());
    }

    private void prepareCreateDependencies() {
        when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        when(conversationMapper.selectById(3L)).thenReturn(conversation());
        when(briefMapper.findByIdAndChapterId(4L, 2L)).thenReturn(brief());
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed")).thenReturn(brief());
        when(outlineMapper.findLatest(2L)).thenReturn(outline(5, 3, contentJson("基础目标")));
    }

    private ChapterEntity chapter() {
        ChapterEntity entity = new ChapterEntity();
        entity.setId(2L);
        entity.setWorkId(1L);
        entity.setDeleted(0);
        return entity;
    }

    private WorkEntity work() {
        WorkEntity entity = new WorkEntity();
        entity.setId(1L);
        entity.setDeleted(0);
        return entity;
    }

    private ChapterConversationEntity conversation() {
        ChapterConversationEntity entity = new ChapterConversationEntity();
        entity.setId(3L);
        entity.setWorkId(1L);
        entity.setChapterId(2L);
        entity.setDeleted(0);
        return entity;
    }

    private ChapterBriefEntity brief() {
        ChapterBriefEntity entity = new ChapterBriefEntity();
        entity.setId(4L);
        entity.setChapterId(2L);
        entity.setBriefStatus("confirmed");
        entity.setBriefContent("已确认共识");
        entity.setDeleted(0);
        return entity;
    }

    private ChapterOutlineEntity outline(int revision, int version, String content) {
        ChapterOutlineEntity entity = new ChapterOutlineEntity();
        entity.setId(6L);
        entity.setWorkId(1L);
        entity.setChapterId(2L);
        entity.setConfirmedBriefId(4L);
        entity.setOutlineStatus("draft");
        entity.setOutlineContent(content);
        entity.setRevision(revision);
        entity.setVersion(version);
        entity.setDeleted(0);
        return entity;
    }

    private ChapterOutlineCandidateEntity readyCandidate() {
        ChapterOutlineCandidateEntity entity = new ChapterOutlineCandidateEntity();
        entity.setId(7L);
        entity.setWorkId(1L);
        entity.setChapterId(2L);
        entity.setConversationId(3L);
        entity.setAiTaskId(8L);
        entity.setConfirmedBriefId(4L);
        entity.setBaseOutlineId(6L);
        entity.setBaseOutlineRevision(5);
        entity.setBaseOutlineContent(contentJson("基础目标"));
        entity.setCandidateStatus("ready");
        entity.setAdjustmentInstruction("强化冲突");
        entity.setCandidateContent(contentJson("候选目标"));
        entity.setVersion(2);
        entity.setDeleted(0);
        return entity;
    }

    private String contentJson(String goal) {
        return contentCodec.write(new OutlineCandidateContent(goal, "核心冲突",
                List.of(new Scene("scene-1", "场景", "内容", List.of("冲突"))), List.of("约束")));
    }
}
