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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.AcceptRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.CreateRequest;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.dto.ChapterGenerationBriefModels.GenerationBriefPreview;
import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证选区候选的发布边界、正文一致性校验和乐观锁采纳。
 */
class SelectionAssistanceServiceImplTest {

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
        verify(fixture.assistanceMapper).update(any(), any());
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

    private static final class Fixture {
        private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
        private final ChapterSelectionAssistanceMapper assistanceMapper = mock(ChapterSelectionAssistanceMapper.class);
        private final AiTaskMapper taskMapper = mock(AiTaskMapper.class);
        private final ChapterGenerationBriefService briefService = mock(ChapterGenerationBriefService.class);
        private final SelectionAssistanceServiceImpl service = new SelectionAssistanceServiceImpl(chapterMapper,
                assistanceMapper, taskMapper, briefService, new ObjectMapper());

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
