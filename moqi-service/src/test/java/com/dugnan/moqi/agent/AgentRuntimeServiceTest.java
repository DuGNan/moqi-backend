package com.dugnan.moqi.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.ResumeAgentRunCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentResumeToken;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
import com.dugnan.moqi.agent.entity.AgentCheckpointEntity;
import com.dugnan.moqi.agent.entity.AgentInterruptionEntity;
import com.dugnan.moqi.agent.entity.AgentRunEntity;
import com.dugnan.moqi.agent.entity.AgentRunStepEntity;
import com.dugnan.moqi.agent.event.AgentInterruptionTokenIssuedEvent;
import com.dugnan.moqi.agent.event.AgentRunEvent;
import com.dugnan.moqi.agent.event.AgentRunSubmittedEvent;
import com.dugnan.moqi.agent.infrastructure.GraphAgentWorkflowInvoker;
import com.dugnan.moqi.agent.mapper.AgentCheckpointMapper;
import com.dugnan.moqi.agent.mapper.AgentInterruptionMapper;
import com.dugnan.moqi.agent.mapper.AgentRunMapper;
import com.dugnan.moqi.agent.mapper.AgentRunStepMapper;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;
import com.dugnan.moqi.work.entity.WorkEntity;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 验证 Agent Runtime 的关键状态迁移和恢复语义。
 */
@ExtendWith(MockitoExtension.class)
class AgentRuntimeServiceTest {

    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentRunStepMapper stepMapper;
    @Mock
    private AgentCheckpointMapper checkpointMapper;
    @Mock
    private AgentInterruptionMapper interruptionMapper;
    @Mock
    private WorkMapper workMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private AiTaskMapper taskMapper;
    @Mock
    private AgentWorkflowRegistry workflowRegistry;
    @Mock
    private GraphAgentWorkflowInvoker graphInvoker;
    @Mock
    private AgentRunCallRegistry callRegistry;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void loadRejectsRunOwnedByAnotherUser() {
        AgentRunEntity run = run(41L, "queued", "draft", 1);
        run.setUserId("another-user");
        when(runMapper.selectById(41L)).thenReturn(run);

        assertThatThrownBy(() -> runtime().load(41L, "local-user"))
                .isInstanceOf(com.dugnan.moqi.common.exception.BusinessException.class)
                .hasMessage("Agent Run 不存在");
    }

    @Test
    void resumeQueuesCheckpointNextStepInsteadOfCompletedStep() throws Exception {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });

        String token = "one-time-token";
        AgentRunEntity waitingRun = run(41L, "waiting_for_human", "draft", 3);
        AgentRunEntity queuedRun = run(41L, "queued", "review", 4);
        when(runMapper.selectById(41L)).thenReturn(waitingRun, queuedRun);

        AgentCheckpointEntity checkpoint = new AgentCheckpointEntity();
        checkpoint.setId(51L);
        checkpoint.setRunId(41L);
        checkpoint.setNextStepKey("review");
        when(checkpointMapper.findLatestByRunId(41L)).thenReturn(checkpoint);

        AgentInterruptionEntity interruption = new AgentInterruptionEntity();
        interruption.setId(61L);
        interruption.setRunId(41L);
        interruption.setCheckpointId(51L);
        interruption.setInterruptionStatus("waiting");
        interruption.setResumeTokenHash(sha256(token));
        interruption.setTokenVersion(1);
        interruption.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        interruption.setVersion(2);
        when(interruptionMapper.findLatestByRunId(41L)).thenReturn(interruption);
        when(interruptionMapper.update(isNull(), any())).thenReturn(1);
        when(runMapper.update(isNull(), any())).thenReturn(1);

        runtime().resume(new ResumeAgentRunCommand(41L, token, 1, Map.of("approved", true)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<AgentRunEntity>> updateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(runMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getParamNameValuePairs())
                .containsValue("review");
    }

    @Test
    void resumedStepAcceptsDatabaseNormalizedCheckpointAndVerifiedHumanResponse() throws Exception {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        AgentRunEntity queuedRun = run(41L, "queued", "review", 4);
        queuedRun.setInputJson("{}");
        AgentRunEntity runningRun = run(41L, "running", "review", 5);
        runningRun.setInputJson("{}");
        AgentRunEntity canceledRun = run(41L, "canceled", "review", 6);
        when(runMapper.selectById(41L))
                .thenReturn(queuedRun, runningRun, runningRun, canceledRun);
        when(runMapper.update(isNull(), any())).thenReturn(1);

        AgentCheckpointEntity checkpoint = new AgentCheckpointEntity();
        checkpoint.setId(51L);
        checkpoint.setRunId(41L);
        checkpoint.setSchemaVersion(1);
        checkpoint.setStateJson("{\"reportId\": 1}");
        checkpoint.setStateHash(sha256("{\"reportId\":1}"));
        checkpoint.setNextStepKey("review");
        when(checkpointMapper.findLatestByRunId(41L)).thenReturn(checkpoint);

        String responseJson = "{\"approved\":true}";
        AgentInterruptionEntity interruption = new AgentInterruptionEntity();
        interruption.setCheckpointId(51L);
        interruption.setInterruptionStatus("resumed");
        interruption.setResponseJson(responseJson);
        interruption.setResponseHash(sha256(responseJson));
        when(interruptionMapper.findLatestByRunId(41L)).thenReturn(interruption);

        AgentWorkflowDefinition workflow = mock(AgentWorkflowDefinition.class);
        when(workflowRegistry.require("chapter-draft")).thenReturn(workflow);
        when(workflow.maxAttempts("review")).thenReturn(2);
        doAnswer(invocation -> {
            AgentRunStepEntity step = invocation.getArgument(0);
            step.setId(71L);
            return 1;
        }).when(stepMapper).insert(any(AgentRunStepEntity.class));
        when(graphInvoker.invoke(any(), any(), any()))
                .thenReturn(AgentStepResult.completed(Map.of(), Map.of(), null));

        runtime().executeQueuedRun(41L);

        ArgumentCaptor<AgentStepExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(AgentStepExecutionContext.class);
        verify(graphInvoker).invoke(any(), any(), contextCaptor.capture());
        assertThat(contextCaptor.getValue().state())
                .containsEntry("reportId", 1);
        assertThat(contextCaptor.getValue().humanResponse())
                .containsEntry("approved", true);
    }

    @Test
    void idempotencyKeyCannotReuseRunForDifferentWork() throws Exception {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });

        WorkEntity requestedWork = new WorkEntity();
        requestedWork.setId(12L);
        requestedWork.setDeleted(0);
        when(workMapper.selectById(12L)).thenReturn(requestedWork);
        AgentWorkflowDefinition workflow = mock(AgentWorkflowDefinition.class);
        when(workflowRegistry.require("chapter-draft")).thenReturn(workflow);

        Map<String, Object> input = Map.of("prompt", "same");
        String inputJson = new ObjectMapper().writeValueAsString(input);
        AgentRunEntity existing = run(41L, "queued", null, 1);
        existing.setUserId("user-1");
        existing.setWorkId(11L);
        existing.setIdempotencyKey("request-1");
        existing.setInputSnapshotVersion(1L);
        existing.setInputHash(sha256(inputJson));
        when(runMapper.selectOne(any())).thenReturn(existing);

        StartAgentRunCommand command = new StartAgentRunCommand(
                "user-1", 12L, null, "chapter-draft", "request-1", 1L, input, null);

        assertThatThrownBy(() -> runtime().start(command))
                .isInstanceOf(com.dugnan.moqi.common.exception.BusinessException.class)
                .hasMessageContaining("幂等键");
    }

    @Test
    void recoveryFailureTerminatesRunTaskStepAndPublishesEvent() {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        AgentRunEntity runningRun = run(41L, "running", "draft", 3);
        runningRun.setAiTaskId(31L);
        AgentRunEntity failedRun = run(41L, "failed", "draft", 4);
        failedRun.setAiTaskId(31L);
        when(runMapper.selectList(any())).thenReturn(List.of(runningRun), List.of());
        when(runMapper.selectById(41L)).thenReturn(runningRun, failedRun);
        when(runMapper.update(isNull(), any())).thenReturn(1);
        when(checkpointMapper.findLatestByRunId(41L)).thenReturn(null);

        runtime().recoverPendingRuns();

        verify(stepMapper).update(isNull(), any());
        verify(taskMapper).update(isNull(), any());
        verify(eventPublisher).publishEvent(isA(AgentRunEvent.class));
    }

    @Test
    void repeatedResumeWithSameConfirmationIsIdempotent() throws Exception {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });

        String token = "used-token";
        String confirmationJson = "{\"approved\":true}";
        AgentRunEntity queuedRun = run(41L, "queued", "review", 4);
        when(runMapper.selectById(41L)).thenReturn(queuedRun);
        AgentInterruptionEntity interruption = new AgentInterruptionEntity();
        interruption.setInterruptionStatus("resumed");
        interruption.setResumeTokenHash(sha256(token));
        interruption.setResponseHash(sha256(confirmationJson));
        interruption.setTokenVersion(1);
        when(interruptionMapper.findLatestByRunId(41L)).thenReturn(interruption);

        runtime().resume(new ResumeAgentRunCommand(41L, token, 1, Map.of("approved", true)));

        verify(runMapper, never()).update(isNull(), any());
        verify(interruptionMapper, never()).update(isNull(), any());
    }

    @Test
    void waitingRunCanReissueLostResumeToken() {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });

        AgentRunEntity waitingRun = run(41L, "waiting_for_human", "review", 4);
        when(runMapper.selectById(41L)).thenReturn(waitingRun);
        AgentInterruptionEntity interruption = new AgentInterruptionEntity();
        interruption.setId(61L);
        interruption.setInterruptionStatus("waiting");
        interruption.setTokenVersion(1);
        interruption.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        interruption.setVersion(2);
        when(interruptionMapper.findLatestByRunId(41L)).thenReturn(interruption);
        when(interruptionMapper.update(isNull(), any())).thenReturn(1);

        AgentResumeToken token = runtime().reissueResumeToken(41L);

        assertThat(token.runId()).isEqualTo(41L);
        assertThat(token.interruptionId()).isEqualTo(61L);
        assertThat(token.resumeToken()).isNotBlank();
        assertThat(token.tokenVersion()).isEqualTo(2);
        assertThat(token.toString()).doesNotContain(token.resumeToken());
        assertThat(new AgentInterruptionTokenIssuedEvent(
                token.runId(), token.interruptionId(), token.resumeToken(), token.tokenVersion()).toString())
                .doesNotContain(token.resumeToken());
        verify(eventPublisher).publishEvent(isA(AgentInterruptionTokenIssuedEvent.class));
    }

    @Test
    void cancelPropagatesToActiveCallStepAndAiTask() {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });

        AgentRunEntity runningRun = run(41L, "running", "draft", 3);
        runningRun.setAiTaskId(31L);
        AgentRunEntity canceledRun = run(41L, "canceled", "draft", 4);
        canceledRun.setAiTaskId(31L);
        when(runMapper.selectById(41L)).thenReturn(runningRun, canceledRun);
        when(runMapper.update(isNull(), any())).thenReturn(1);

        runtime().cancel(41L);

        verify(callRegistry).cancel(41L, true);
        verify(stepMapper).update(isNull(), any());
        verify(interruptionMapper).update(isNull(), any());
        verify(taskMapper).update(isNull(), any());
        verify(eventPublisher).publishEvent(isA(AgentRunEvent.class));
    }

    @Test
    void expiredWaitingRunIsTimedOutConsistently() {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        AgentRunEntity waitingRun = run(41L, "waiting_for_human", "review", 3);
        waitingRun.setTimeoutAt(LocalDateTime.now().minusMinutes(1));
        AgentRunEntity timedOutRun = run(41L, "timed_out", "review", 4);
        when(runMapper.selectList(any())).thenReturn(List.of(waitingRun));
        when(runMapper.selectById(41L)).thenReturn(waitingRun, timedOutRun);
        when(runMapper.update(isNull(), any())).thenReturn(1);

        runtime().timeoutExpiredRuns();

        verify(callRegistry).cancel(41L, false);
        verify(stepMapper).update(isNull(), any());
        verify(interruptionMapper).update(isNull(), any());
        verify(eventPublisher).publishEvent(isA(AgentRunEvent.class));
    }

    @Test
    void retryQueuesNextAttemptOnlyForRetryableFailedStep() {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });

        AgentRunEntity failedRun = run(41L, "failed", "draft", 3);
        AgentRunEntity queuedRun = run(41L, "queued", "draft", 4);
        when(runMapper.selectById(41L)).thenReturn(failedRun, queuedRun);
        AgentRunStepEntity failedStep = new AgentRunStepEntity();
        failedStep.setId(71L);
        failedStep.setRunId(41L);
        failedStep.setStepKey("draft");
        failedStep.setAttempt(1);
        failedStep.setStepStatus("failed");
        failedStep.setRetryable(1);
        when(stepMapper.selectOne(any())).thenReturn(failedStep);
        AgentWorkflowDefinition workflow = mock(AgentWorkflowDefinition.class);
        when(workflowRegistry.require("chapter-draft")).thenReturn(workflow);
        when(workflow.maxAttempts("draft")).thenReturn(2);
        when(workflow.timeout()).thenReturn(Duration.ofMinutes(30));
        when(runMapper.update(isNull(), any())).thenReturn(1);
        LocalDateTime beforeRetry = LocalDateTime.now();

        runtime().retryStep(new RetryAgentStepCommand(41L, "draft", 1));

        verify(eventPublisher).publishEvent(isA(AgentRunSubmittedEvent.class));
        ArgumentCaptor<UpdateWrapper<AgentRunEntity>> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(runMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getParamNameValuePairs().values())
                .anyMatch(value -> value instanceof LocalDateTime timeoutAt
                        && timeoutAt.isAfter(beforeRetry.plusMinutes(29)));
    }

    @Test
    void terminalAiTaskCannotBeBoundToNewRun() {
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });

        WorkEntity work = new WorkEntity();
        work.setId(11L);
        work.setDeleted(0);
        when(workMapper.selectById(11L)).thenReturn(work);
        com.dugnan.moqi.chapter.entity.AiTaskEntity task =
                new com.dugnan.moqi.chapter.entity.AiTaskEntity();
        task.setId(31L);
        task.setWorkId(11L);
        task.setTaskStatus("succeeded");
        task.setDeleted(0);
        when(taskMapper.selectById(31L)).thenReturn(task);

        StartAgentRunCommand command = new StartAgentRunCommand(
                "user-1", 11L, null, "chapter-draft", "request-1", 1L, Map.of(), 31L);

        assertThatThrownBy(() -> runtime().start(command))
                .isInstanceOf(com.dugnan.moqi.common.exception.BusinessException.class)
                .hasMessageContaining("终态");
    }

    @Test
    void workflowCanProvideBoundedSafeFailureMessage() {
        AgentWorkflowDefinition workflow = mock(AgentWorkflowDefinition.class);
        String safeMessage = "影响分析证据输出字段 changes[0].evidenceText 不符合安全契约：invalid_reference";
        when(workflow.errorMessage(any())).thenReturn(safeMessage.repeat(20));

        String persisted = runtime().safeMessage(workflow, new IllegalStateException("原始敏感消息"));

        assertThat(persisted).hasSize(500).startsWith(safeMessage).doesNotContain("原始敏感消息");
    }

    @Test
    void workflowWithoutSafeFailureMessageFallsBackToRuntimeMessage() {
        AgentWorkflowDefinition workflow = mock(AgentWorkflowDefinition.class);

        assertThat(runtime().safeMessage(workflow, new IllegalStateException("运行时安全消息")))
                .isEqualTo("运行时安全消息");
    }

    private AgentRuntimeService runtime() {
        return new AgentRuntimeService(
                runMapper,
                stepMapper,
                checkpointMapper,
                interruptionMapper,
                workMapper,
                chapterMapper,
                taskMapper,
                workflowRegistry,
                graphInvoker,
                callRegistry,
                new ObjectMapper(),
                transactionTemplate,
                eventPublisher);
    }

    private AgentRunEntity run(Long id, String status, String currentStepKey, int version) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(id);
        run.setWorkflowType("chapter-draft");
        run.setRunStatus(status);
        run.setWorkId(11L);
        run.setChapterId(21L);
        run.setCurrentStepKey(currentStepKey);
        run.setCheckpointSequence(1L);
        run.setTimeoutAt(LocalDateTime.now().plusMinutes(10));
        run.setDeleted(0);
        run.setVersion(version);
        return run;
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
