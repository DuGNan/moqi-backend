package com.dugnan.moqi.chapter.title;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterTitleCandidateBatchEntity;
import com.dugnan.moqi.chapter.entity.ChapterTitleCandidateEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterTitleCandidateBatchMapper;
import com.dugnan.moqi.chapter.mapper.ChapterTitleCandidateMapper;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.AdoptRequest;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.CreateBatchRequest;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 验证章节取名正文归属、冻结输入、旧依据和幂等采用边界。
 */
@ExtendWith(MockitoExtension.class)
class ChapterTitleCandidateServiceImplTest {

    @Mock private ChapterMapper chapterMapper;
    @Mock private WorkMapper workMapper;
    @Mock private ChapterProseCandidateMapper proseCandidateMapper;
    @Mock private ChapterTitleCandidateBatchMapper batchMapper;
    @Mock private ChapterTitleCandidateMapper candidateMapper;
    @Mock private AiTaskMapper taskMapper;
    @Mock private AgentRuntime agentRuntime;
    private ChapterTitleCandidateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChapterTitleCandidateServiceImpl(chapterMapper, workMapper, proseCandidateMapper,
                batchMapper, candidateMapper, taskMapper);
        service.setAgentRuntime(agentRuntime);
    }

    @Test
    void freezesSavedFormalProseAndStartsRecoverableRun() {
        ChapterEntity chapter = chapter("雨水从门缝漫进来", 3);
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("潮汐边界");
        work.setDeleted(0);
        when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter);
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter));
        when(workMapper.selectById(1L)).thenReturn(work);
        when(batchMapper.selectOne(any())).thenReturn(null);
        when(candidateMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            AiTaskEntity task = invocation.getArgument(0);
            task.setId(7L);
            return 1;
        });
        AtomicReference<ChapterTitleCandidateBatchEntity> savedBatch = new AtomicReference<>();
        when(batchMapper.insert(any(ChapterTitleCandidateBatchEntity.class))).thenAnswer(invocation -> {
            ChapterTitleCandidateBatchEntity batch = invocation.getArgument(0);
            batch.setId(9L);
            batch.setGmtCreate(LocalDateTime.now());
            batch.setGmtModified(batch.getGmtCreate());
            savedBatch.set(batch);
            return 1;
        });
        when(batchMapper.selectById(9L)).thenAnswer(invocation -> savedBatch.get());
        when(agentRuntime.start(any())).thenReturn(new AgentRunView(11L,
                ChapterTitleCandidateServiceImpl.WORKFLOW_TYPE, "queued", 1L, 2L, 7L,
                ChapterTitleCandidateServiceImpl.GENERATE_STEP, 0L, null, null,
                LocalDateTime.now().plusMinutes(10), null, null));

        service.create(2L, new CreateBatchRequest("formal", "formal:2", 3,
                hash("雨水从门缝漫进来"), "create-key"));

        assertThat(savedBatch.get().getSourceContentSnapshot()).isEqualTo("雨水从门缝漫进来");
        assertThat(savedBatch.get().getPromptContent()).contains("作品：潮汐边界", "章序：第 2 章", "已保存正文");
        ArgumentCaptor<StartAgentRunCommand> command = ArgumentCaptor.forClass(StartAgentRunCommand.class);
        verify(agentRuntime).start(command.capture());
        assertThat(command.getValue().workflowType()).isEqualTo(ChapterTitleCandidateServiceImpl.WORKFLOW_TYPE);
        assertThat(command.getValue().input()).containsEntry("batchId", 9L).containsEntry("aiTaskId", 7L);
    }

    @Test
    void rejectsCandidateSourceOutsideTheChapter() {
        when(chapterMapper.selectByIdForUpdate(2L)).thenReturn(chapter("正式正文", 1));
        when(batchMapper.selectOne(any())).thenReturn(null);
        when(proseCandidateMapper.selectByIdForUpdate(2L, 99L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(2L, new CreateBatchRequest(
                "candidate", "candidate:99", 1, "a".repeat(64), "candidate-key")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PROSE_CANDIDATE_NOT_FOUND);
        verify(taskMapper, never()).insert(any(AiTaskEntity.class));
    }

    @Test
    void refusesStaleSourceUntilAuthorExplicitlyConfirms() {
        ChapterTitleCandidateBatchEntity batch = completedBatch();
        ChapterTitleCandidateEntity candidate = candidate();
        when(batchMapper.selectByIdForUpdate(9L)).thenReturn(batch);
        when(candidateMapper.selectOne(any())).thenReturn(null);
        when(candidateMapper.selectByIdForUpdate(9L, 12L)).thenReturn(candidate);
        when(chapterMapper.selectById(2L)).thenReturn(chapter("正文已经修改", 4));

        assertThatThrownBy(() -> service.adopt(2L, 9L, 12L,
                new AdoptRequest("潮痕", 4, "adopt-key", true, false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PROSE_CANDIDATE_CONFLICT);
        verify(chapterMapper, never()).updateTitleIfVersion(any(), any(), any());
    }

    @Test
    void adoptsEditedCandidateWithChapterCasAfterStaleConfirmation() {
        ChapterTitleCandidateBatchEntity batch = completedBatch();
        ChapterTitleCandidateEntity candidate = candidate();
        when(batchMapper.selectByIdForUpdate(9L)).thenReturn(batch);
        when(candidateMapper.selectOne(any())).thenReturn(null);
        when(candidateMapper.selectByIdForUpdate(9L, 12L)).thenReturn(candidate);
        when(chapterMapper.selectById(2L)).thenReturn(chapter("正文已经修改", 4));
        when(chapterMapper.updateTitleIfVersion(2L, "潮痕之后", 4)).thenReturn(1);

        var result = service.adopt(2L, 9L, 12L,
                new AdoptRequest("潮痕之后", 4, "adopt-key", true, true));

        assertThat(result.title()).isEqualTo("潮痕之后");
        assertThat(result.chapterVersion()).isEqualTo(5);
        assertThat(result.idempotentReplay()).isFalse();
    }

    @Test
    void replaysTheFirstAdoptionOnlyForTheSameBatchCandidateAndTitle() {
        ChapterTitleCandidateBatchEntity batch = completedBatch();
        ChapterTitleCandidateEntity adopted = candidate();
        adopted.setAdoptedTitle("潮痕");
        adopted.setAdoptionIdempotencyKey("adopt-key");
        adopted.setAdoptedChapterVersion(5);
        adopted.setAdoptedAt(LocalDateTime.now());
        when(batchMapper.selectByIdForUpdate(9L)).thenReturn(batch);
        when(candidateMapper.selectOne(any())).thenReturn(adopted);

        var replay = service.adopt(2L, 9L, 12L,
                new AdoptRequest("潮痕", 4, "adopt-key", true, false));

        assertThat(replay.idempotentReplay()).isTrue();
        verify(chapterMapper, never()).updateTitleIfVersion(any(), any(), any());
    }

    @Test
    void rejectsInvalidModelTitlesWithoutPersistingPartialCandidates() {
        when(batchMapper.selectByIdForUpdate(9L)).thenReturn(completedBatch());

        assertThatThrownBy(() -> service.complete(9L, List.of("潮痕", "第2章 雾中来客", "失约的钟声")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("章序");
        verify(candidateMapper, never()).insert(any(ChapterTitleCandidateEntity.class));
    }

    @Test
    void lateProviderCompletionCannotOverrideCanceledBatch() {
        ChapterTitleCandidateBatchEntity batch = completedBatch();
        batch.setBatchStatus("canceled");
        when(batchMapper.selectByIdForUpdate(9L)).thenReturn(batch);

        service.complete(9L, List.of("潮痕", "雾中来客", "失约的钟声"));
        service.fail(9L, "CHAPTER_TITLE_PROVIDER_FAILED", "provider detail");

        verify(candidateMapper, never()).insert(any(ChapterTitleCandidateEntity.class));
        verify(taskMapper, never()).update(any(), any());
        verify(batchMapper, never()).update(any(), any());
    }

    private ChapterEntity chapter(String content, int version) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(2L);
        chapter.setWorkId(1L);
        chapter.setChapterNo(2);
        chapter.setTitle(null);
        chapter.setContent(content);
        chapter.setVersion(version);
        chapter.setDeleted(0);
        return chapter;
    }

    private ChapterTitleCandidateBatchEntity completedBatch() {
        ChapterTitleCandidateBatchEntity batch = new ChapterTitleCandidateBatchEntity();
        batch.setId(9L);
        batch.setWorkId(1L);
        batch.setChapterId(2L);
        batch.setAiTaskId(7L);
        batch.setBatchStatus("completed");
        batch.setSourceKind("formal");
        batch.setSourceObjectId("formal:2");
        batch.setSourceVersion(3);
        batch.setSourceContentHash(hash("原冻结正文"));
        batch.setCurrentAttempt(1);
        batch.setDeleted(0);
        batch.setVersion(1);
        return batch;
    }

    private ChapterTitleCandidateEntity candidate() {
        ChapterTitleCandidateEntity candidate = new ChapterTitleCandidateEntity();
        candidate.setId(12L);
        candidate.setBatchId(9L);
        candidate.setCandidateOrder(1);
        candidate.setTitle("潮痕");
        candidate.setDeleted(0);
        candidate.setVersion(0);
        return candidate;
    }

    private String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
