package com.dugnan.moqi.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentInterruptionRequest;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentResumeToken;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.ResumeAgentRunCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
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
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * 基于 MySQL 状态机的框架无关 Agent Runtime 实现。
 *
 * @author dgn
 * @date 2026-08-01
 * @description 编排 Agent Run 状态迁移、步骤执行、检查点和人工恢复。
 */
@Service
public class AgentRuntimeService implements AgentRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntimeService.class);
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_WAITING = "waiting_for_human";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELED = "canceled";
    private static final String STATUS_TIMED_OUT = "timed_out";
    private static final String INTERRUPTION_WAITING = "waiting";
    private static final String INTERRUPTION_RESUMED = "resumed";
    private static final int CHECKPOINT_SCHEMA_VERSION = 1;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AgentRunMapper runMapper;
    private final AgentRunStepMapper stepMapper;
    private final AgentCheckpointMapper checkpointMapper;
    private final AgentInterruptionMapper interruptionMapper;
    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final AiTaskMapper taskMapper;
    private final AgentWorkflowRegistry workflowRegistry;
    private final GraphAgentWorkflowInvoker graphInvoker;
    private final AgentRunCallRegistry callRegistry;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public AgentRuntimeService(
            AgentRunMapper runMapper,
            AgentRunStepMapper stepMapper,
            AgentCheckpointMapper checkpointMapper,
            AgentInterruptionMapper interruptionMapper,
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            AiTaskMapper taskMapper,
            AgentWorkflowRegistry workflowRegistry,
            GraphAgentWorkflowInvoker graphInvoker,
            AgentRunCallRegistry callRegistry,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            ApplicationEventPublisher eventPublisher) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.checkpointMapper = checkpointMapper;
        this.interruptionMapper = interruptionMapper;
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.taskMapper = taskMapper;
        this.workflowRegistry = workflowRegistry;
        this.graphInvoker = graphInvoker;
        this.callRegistry = callRegistry;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public AgentRunView start(StartAgentRunCommand command) {
        requireStartCommand(command);
        return transactionTemplate.execute(status -> createOrLoadRun(command));
    }

    @Override
    public Optional<AgentRunView> findByIdempotencyKey(String userId, String workflowType, String idempotencyKey) {
        if (blank(userId) || blank(workflowType) || blank(idempotencyKey)) {
            return Optional.empty();
        }
        AgentRunEntity run = runMapper.selectOne(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getUserId, userId)
                .eq(AgentRunEntity::getWorkflowType, workflowType)
                .eq(AgentRunEntity::getIdempotencyKey, idempotencyKey)
                .eq(AgentRunEntity::getDeleted, 0));
        return Optional.ofNullable(run).map(this::view);
    }

    @Override
    public AgentRunView load(Long runId, String userId) {
        AgentRunEntity run = requireRun(runId);
        if (blank(userId) || !userId.equals(run.getUserId())) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent Run 不存在");
        }
        return view(run);
    }

    @Override
    public AgentRunView resume(ResumeAgentRunCommand command) {
        if (command == null || command.runId() == null || command.resumeToken() == null
                || command.tokenVersion() == null || command.confirmation() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "恢复参数不完整");
        }
        return transactionTemplate.execute(status -> resumeInTransaction(command));
    }

    @Override
    public AgentRunView retryStep(RetryAgentStepCommand command) {
        if (command == null || command.runId() == null || command.stepKey() == null
                || command.expectedAttempt() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "重试参数不完整");
        }
        return transactionTemplate.execute(status -> retryInTransaction(command));
    }

    @Override
    public AgentResumeToken reissueResumeToken(Long runId) {
        return transactionTemplate.execute(status -> reissueResumeTokenInTransaction(runId));
    }

    @Override
    public AgentRunView cancel(Long runId) {
        return transactionTemplate.execute(status -> cancelInTransaction(runId, STATUS_CANCELED, null));
    }

    /** 由执行器调用；只有 queued Run 能被一个工作线程领取。 */
    public void executeQueuedRun(Long runId) {
        callRegistry.beginExecution(runId);
        try {
            PreparedStep prepared = transactionTemplate.execute(status -> claimAndPrepare(runId));
            if (prepared == null) {
                return;
            }
            try {
                AgentStepResult result = graphInvoker.invoke(
                        prepared.definition(), prepared.step().getStepKey(), prepared.context());
                transactionTemplate.executeWithoutResult(status -> completeStep(prepared, result));
            } catch (Exception exception) {
                LOGGER.error(
                        "Agent 步骤执行发生未预期异常，runId={}, stepKey={}, attempt={}, exceptionType={}",
                        runId,
                        prepared.step().getStepKey(),
                        prepared.step().getAttempt(),
                        exception.getClass().getName(),
                        exception);
                transactionTemplate.executeWithoutResult(status -> failStep(prepared, exception));
            }
        } finally {
            callRegistry.endExecution(runId);
        }
    }

    /** 有界执行器拒绝任务时将 Run 标记为可追溯失败，而不是伪造成取消。 */
    public void rejectExecution(Long runId) {
        transactionTemplate.executeWithoutResult(status ->
                cancelInTransaction(runId, STATUS_FAILED, "AGENT_EXECUTOR_REJECTED"));
    }

    /** 启动时先快照遗留 running Run，再派发 queued，避免把本次启动的新领取误判为重启遗留。 */
    public void recoverPendingRuns() {
        List<AgentRunEntity> running = runMapper.selectList(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getDeleted, 0)
                .eq(AgentRunEntity::getRunStatus, STATUS_RUNNING)
                .orderByAsc(AgentRunEntity::getId)
                .last("LIMIT 100"));
        dispatchQueuedRuns();
        running.forEach(run -> transactionTemplate.executeWithoutResult(status -> recoverRunningRun(run.getId())));
    }

    /** 周期扫描仅重新派发 queued Run，不接触可能仍由当前进程执行的 running Run。 */
    public void dispatchQueuedRuns() {
        List<AgentRunEntity> queued = runMapper.selectList(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getDeleted, 0)
                .eq(AgentRunEntity::getRunStatus, STATUS_QUEUED)
                .orderByAsc(AgentRunEntity::getId)
                .last("LIMIT 100"));
        queued.forEach(run -> eventPublisher.publishEvent(new AgentRunSubmittedEvent(run.getId())));
    }

    /** 周期性将已到期但尚未终止的 Run 转为 timed_out。 */
    public void timeoutExpiredRuns() {
        List<AgentRunEntity> expired = runMapper.selectList(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getDeleted, 0)
                .in(AgentRunEntity::getRunStatus, Set.of(STATUS_QUEUED, STATUS_RUNNING, STATUS_WAITING))
                .lt(AgentRunEntity::getTimeoutAt, LocalDateTime.now())
                .last("LIMIT 100"));
        expired.forEach(run -> transactionTemplate.executeWithoutResult(status ->
                cancelInTransaction(run.getId(), STATUS_TIMED_OUT, ErrorCode.AGENT_RUN_TIMED_OUT.name())));
    }

    private AgentRunView createOrLoadRun(StartAgentRunCommand command) {
        validateOwnership(command);
        AgentWorkflowDefinition definition = workflowRegistry.require(command.workflowType());
        String inputJson = json(command.input());
        String inputHash = sha256(inputJson);
        AgentRunEntity existing = runMapper.selectOne(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getUserId, command.userId())
                .eq(AgentRunEntity::getWorkflowType, command.workflowType())
                .eq(AgentRunEntity::getIdempotencyKey, command.idempotencyKey())
                .eq(AgentRunEntity::getDeleted, 0));
        if (existing != null) {
            if (isSameStartRequest(existing, command, inputHash)) {
                return view(existing);
            }
            throw conflict(ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT, "幂等键已绑定不同输入");
        }
        AgentRunEntity run = new AgentRunEntity();
        run.setUserId(command.userId());
        run.setWorkId(command.workId());
        run.setChapterId(command.chapterId());
        run.setAiTaskId(command.aiTaskId());
        run.setWorkflowType(command.workflowType());
        run.setIdempotencyKey(command.idempotencyKey());
        run.setInputSnapshotVersion(command.inputSnapshotVersion());
        run.setInputJson(inputJson);
        run.setInputHash(inputHash);
        run.setRunStatus(STATUS_QUEUED);
        run.setCheckpointSequence(0L);
        run.setTimeoutAt(LocalDateTime.now().plus(definition.timeout()));
        run.setDeleted(0);
        run.setVersion(0);
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException exception) {
            AgentRunEntity concurrent = runMapper.selectOne(new LambdaQueryWrapper<AgentRunEntity>()
                    .eq(AgentRunEntity::getUserId, command.userId())
                    .eq(AgentRunEntity::getWorkflowType, command.workflowType())
                    .eq(AgentRunEntity::getIdempotencyKey, command.idempotencyKey())
                    .eq(AgentRunEntity::getDeleted, 0));
            if (concurrent != null && isSameStartRequest(concurrent, command, inputHash)) {
                return view(concurrent);
            }
            throw conflict(ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT, "幂等键竞争失败");
        }
        publish(run, null, null, null);
        eventPublisher.publishEvent(new AgentRunSubmittedEvent(run.getId()));
        return view(run);
    }

    private AgentRunView resumeInTransaction(ResumeAgentRunCommand command) {
        AgentRunEntity run = requireRun(command.runId());
        AgentInterruptionEntity interruption = interruptionMapper.findLatestByRunId(run.getId());
        String confirmationJson = json(command.confirmation());
        if (interruption == null || !sha256(command.resumeToken()).equals(interruption.getResumeTokenHash())
                || !command.tokenVersion().equals(interruption.getTokenVersion())) {
            throw conflict(ErrorCode.AGENT_RESUME_TOKEN_INVALID, "恢复令牌无效");
        }
        if (INTERRUPTION_RESUMED.equals(interruption.getInterruptionStatus())) {
            if (sha256(confirmationJson).equals(interruption.getResponseHash())) {
                return view(run);
            }
            throw conflict(ErrorCode.AGENT_RESUME_TOKEN_INVALID, "恢复令牌已用于不同确认内容");
        }
        if (!INTERRUPTION_WAITING.equals(interruption.getInterruptionStatus())
                || interruption.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw conflict(ErrorCode.AGENT_RESUME_TOKEN_INVALID, "恢复令牌已过期或不可用");
        }
        AgentCheckpointEntity checkpoint = checkpointMapper.findLatestByRunId(run.getId());
        if (checkpoint == null || !checkpoint.getId().equals(interruption.getCheckpointId())
                || blank(checkpoint.getNextStepKey())) {
            throw conflict(ErrorCode.AGENT_CHECKPOINT_INVALID, "人工恢复缺少有效的下一步骤");
        }
        int changedInterruption = interruptionMapper.update(null, new UpdateWrapper<AgentInterruptionEntity>()
                .eq("id", interruption.getId()).eq("deleted", 0).eq("version", interruption.getVersion())
                .eq("interruption_status", INTERRUPTION_WAITING)
                .set("interruption_status", INTERRUPTION_RESUMED)
                .set("response_json", confirmationJson).set("response_hash", sha256(confirmationJson))
                .set("resumed_at", LocalDateTime.now()).set("version", interruption.getVersion() + 1));
        int changedRun = runMapper.update(null, new UpdateWrapper<AgentRunEntity>()
                .eq("id", run.getId()).eq("deleted", 0).eq("version", run.getVersion())
                .eq("run_status", STATUS_WAITING).set("run_status", STATUS_QUEUED)
                .set("current_step_key", checkpoint.getNextStepKey())
                .set("version", run.getVersion() + 1));
        if (changedInterruption != 1 || changedRun != 1) {
            throw conflict(ErrorCode.AGENT_RUN_STATE_CONFLICT, "Agent Run 状态已变化");
        }
        AgentRunEntity resumed = requireRun(run.getId());
        publish(resumed, null, null, interruption.getId());
        eventPublisher.publishEvent(new AgentRunSubmittedEvent(run.getId()));
        return view(resumed);
    }

    private AgentRunView retryInTransaction(RetryAgentStepCommand command) {
        AgentRunEntity run = requireRun(command.runId());
        AgentRunStepEntity step = latestStep(run.getId(), command.stepKey());
        if (step == null) {
            throw new BusinessException(ErrorCode.AGENT_STEP_NOT_FOUND, "Agent Step 不存在");
        }
        if (!STATUS_FAILED.equals(step.getStepStatus()) || !command.expectedAttempt().equals(step.getAttempt())
                || !Integer.valueOf(1).equals(step.getRetryable())) {
            throw conflict(ErrorCode.AGENT_RUN_STATE_CONFLICT, "当前步骤不可重试");
        }
        AgentWorkflowDefinition definition = workflowRegistry.require(run.getWorkflowType());
        if (step.getAttempt() >= definition.maxAttempts(step.getStepKey())) {
            throw conflict(ErrorCode.AGENT_STEP_RETRY_EXHAUSTED, "步骤已达到重试上限");
        }
        int changed = runMapper.update(null, new UpdateWrapper<AgentRunEntity>()
                .eq("id", run.getId()).eq("deleted", 0).eq("version", run.getVersion())
                .eq("run_status", STATUS_FAILED).set("run_status", STATUS_QUEUED)
                .set("current_step_key", step.getStepKey()).set("error_code", null).set("error_message", null)
                .set("version", run.getVersion() + 1));
        if (changed != 1) {
            throw conflict(ErrorCode.AGENT_RUN_STATE_CONFLICT, "Agent Run 状态已变化");
        }
        AgentRunEntity retried = requireRun(run.getId());
        publish(retried, step, null, null);
        eventPublisher.publishEvent(new AgentRunSubmittedEvent(run.getId()));
        return view(retried);
    }

    private AgentResumeToken reissueResumeTokenInTransaction(Long runId) {
        AgentRunEntity run = requireRun(runId);
        if (!STATUS_WAITING.equals(run.getRunStatus())) {
            throw conflict(ErrorCode.AGENT_RUN_STATE_CONFLICT, "Agent Run 不在人工等待状态");
        }
        AgentInterruptionEntity interruption = interruptionMapper.findLatestByRunId(runId);
        if (interruption == null || !INTERRUPTION_WAITING.equals(interruption.getInterruptionStatus())
                || interruption.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw conflict(ErrorCode.AGENT_RESUME_TOKEN_INVALID, "人工中断已过期或不可用");
        }
        String token = newResumeToken();
        int tokenVersion = interruption.getTokenVersion() + 1;
        int changed = interruptionMapper.update(null, new UpdateWrapper<AgentInterruptionEntity>()
                .eq("id", interruption.getId())
                .eq("deleted", 0)
                .eq("version", interruption.getVersion())
                .eq("interruption_status", INTERRUPTION_WAITING)
                .set("resume_token_hash", sha256(token))
                .set("token_version", tokenVersion)
                .set("version", interruption.getVersion() + 1));
        if (changed != 1) {
            throw conflict(ErrorCode.AGENT_RUN_STATE_CONFLICT, "人工中断状态已变化");
        }
        eventPublisher.publishEvent(new AgentInterruptionTokenIssuedEvent(
                runId, interruption.getId(), token, tokenVersion));
        return new AgentResumeToken(runId, interruption.getId(), token, tokenVersion);
    }

    private AgentRunView cancelInTransaction(Long runId, String targetStatus, String errorCode) {
        AgentRunEntity run = requireRun(runId);
        if (Set.of(STATUS_SUCCEEDED, STATUS_FAILED, STATUS_CANCELED, STATUS_TIMED_OUT).contains(run.getRunStatus())) {
            return view(run);
        }
        int changed = runMapper.update(null, new UpdateWrapper<AgentRunEntity>()
                .eq("id", run.getId()).eq("deleted", 0).eq("version", run.getVersion())
                .in("run_status", STATUS_QUEUED, STATUS_RUNNING, STATUS_WAITING)
                .set("run_status", targetStatus).set("error_code", errorCode)
                .set("error_message", STATUS_TIMED_OUT.equals(targetStatus) ? "Agent Run 已超时" : null)
                .set("version", run.getVersion() + 1));
        if (changed != 1) {
            return view(requireRun(runId));
        }
        callRegistry.cancel(runId, STATUS_RUNNING.equals(run.getRunStatus()));
        markActiveStepTerminal(runId, targetStatus);
        terminateWaitingInterruptions(runId, targetStatus);
        if (run.getAiTaskId() != null) {
            updateAiTaskTerminal(run.getAiTaskId(), targetStatus);
        }
        AgentRunEntity canceled = requireRun(runId);
        publish(canceled, null, null, null);
        return view(canceled);
    }

    private PreparedStep claimAndPrepare(Long runId) {
        AgentRunEntity run = requireRun(runId);
        if (!STATUS_QUEUED.equals(run.getRunStatus())) {
            return null;
        }
        if (run.getTimeoutAt().isBefore(LocalDateTime.now())) {
            cancelInTransaction(runId, STATUS_TIMED_OUT, ErrorCode.AGENT_RUN_TIMED_OUT.name());
            return null;
        }
        if (run.getAiTaskId() != null && isAiTaskCanceled(run.getAiTaskId())) {
            cancelInTransaction(runId, STATUS_CANCELED, null);
            return null;
        }
        int claimed = runMapper.update(null, new UpdateWrapper<AgentRunEntity>()
                .eq("id", runId).eq("deleted", 0).eq("version", run.getVersion()).eq("run_status", STATUS_QUEUED)
                .set("run_status", STATUS_RUNNING).set("version", run.getVersion() + 1));
        if (claimed != 1) {
            return null;
        }
        AgentRunEntity running = requireRun(runId);
        AgentWorkflowDefinition definition = workflowRegistry.require(running.getWorkflowType());
        AgentCheckpointEntity checkpoint = checkpointMapper.findLatestByRunId(runId);
        Map<String, Object> state = checkpoint == null ? Map.of() : state(checkpoint);
        Map<String, Object> humanResponse = humanResponse(checkpoint);
        String stepKey = running.getCurrentStepKey();
        if (stepKey == null || stepKey.isBlank()) {
            stepKey = checkpoint == null || checkpoint.getNextStepKey() == null
                    ? definition.startStepKey() : checkpoint.getNextStepKey();
        }
        AgentRunStepEntity previous = latestStep(runId, stepKey);
        int attempt = previous == null ? 1 : previous.getAttempt() + 1;
        if (attempt > definition.maxAttempts(stepKey)) {
            throw conflict(ErrorCode.AGENT_STEP_RETRY_EXHAUSTED, "步骤已达到重试上限");
        }
        AgentRunStepEntity step = new AgentRunStepEntity();
        step.setRunId(runId);
        step.setStepKey(stepKey);
        step.setAttempt(attempt);
        step.setStepStatus(STATUS_RUNNING);
        step.setInputSummaryJson(running.getInputJson());
        step.setRetryable(0);
        step.setStartedAt(LocalDateTime.now());
        step.setDeleted(0);
        step.setVersion(0);
        stepMapper.insert(step);
        runMapper.update(null, new UpdateWrapper<AgentRunEntity>().eq("id", runId).eq("deleted", 0)
                .set("current_step_key", stepKey));
        markAiTaskRunning(running.getAiTaskId());
        AgentStepExecutionContext context = new AgentStepExecutionContext(
                runId, step.getId(), stepKey, attempt, runId + ":" + stepKey,
                map(running.getInputJson()), state, humanResponse, callRegistry);
        AgentRunEntity updated = requireRun(runId);
        publish(updated, step, checkpoint, null);
        return new PreparedStep(updated, step, checkpoint, definition, context);
    }

    private void completeStep(PreparedStep prepared, AgentStepResult result) {
        AgentRunEntity run = requireRun(prepared.run().getId());
        if (!STATUS_RUNNING.equals(run.getRunStatus()) || callRegistry.isCancellationRequested(run.getId())) {
            return;
        }
        AgentRunStepEntity step = requireStep(prepared.step().getId());
        if (!STATUS_RUNNING.equals(step.getStepStatus())) {
            return;
        }
        prepared.definition().applyResult(step.getStepKey(), prepared.context(), result);
        int stepChanged = stepMapper.update(null, new UpdateWrapper<AgentRunStepEntity>()
                .eq("id", step.getId()).eq("deleted", 0).eq("version", step.getVersion())
                .eq("step_status", STATUS_RUNNING).set("step_status", STATUS_SUCCEEDED)
                .set("output_summary_json", json(result.outputSummary()))
                .set("model_call_ref", result.modelCallRef()).set("finished_at", LocalDateTime.now())
                .set("version", step.getVersion() + 1));
        if (stepChanged != 1) {
            return;
        }
        AgentCheckpointEntity checkpoint = saveCheckpoint(run, step, result);
        AgentRunEntity checkpointedRun = requireRun(run.getId());
        AgentInterruptionRequest interruptionRequest = result.interruption();
        if (interruptionRequest != null) {
            AgentInterruptionEntity interruption = createInterruption(run, step, checkpoint, interruptionRequest);
            updateRunStatus(checkpointedRun, STATUS_WAITING, null, null, step.getStepKey());
            AgentRunEntity waiting = requireRun(run.getId());
            publish(waiting, requireStep(step.getId()), checkpoint, interruption.getId());
            return;
        }
        String nextStepKey = result.nextStepKey();
        String target = nextStepKey == null || nextStepKey.isBlank() ? STATUS_SUCCEEDED : STATUS_QUEUED;
        updateRunStatus(checkpointedRun, target, null, null, nextStepKey);
        if (STATUS_SUCCEEDED.equals(target) && run.getAiTaskId() != null) {
            updateAiTaskTerminal(run.getAiTaskId(), STATUS_SUCCEEDED);
        }
        AgentRunEntity updated = requireRun(run.getId());
        publish(updated, requireStep(step.getId()), checkpoint, null);
        if (STATUS_QUEUED.equals(target)) {
            eventPublisher.publishEvent(new AgentRunSubmittedEvent(run.getId()));
        }
    }

    private void failStep(PreparedStep prepared, Exception exception) {
        AgentRunEntity run = requireRun(prepared.run().getId());
        if (!STATUS_RUNNING.equals(run.getRunStatus())) {
            return;
        }
        AgentRunStepEntity step = requireStep(prepared.step().getId());
        boolean retryable = step.getAttempt() < prepared.definition().maxAttempts(step.getStepKey());
        String errorCategory = prepared.definition().errorCategory(exception);
        String errorCode = prepared.definition().errorCode(exception);
        stepMapper.update(null, new UpdateWrapper<AgentRunStepEntity>()
                .eq("id", step.getId()).eq("deleted", 0).eq("version", step.getVersion())
                .eq("step_status", STATUS_RUNNING).set("step_status", STATUS_FAILED)
                .set("retryable", retryable ? 1 : 0).set("error_category", errorCategory)
                .set("error_code", errorCode)
                .set("error_message", safeMessage(exception)).set("finished_at", LocalDateTime.now())
                .set("version", step.getVersion() + 1));
        prepared.definition().applyFailure(step.getStepKey(), prepared.context(), exception);
        updateRunStatus(run, STATUS_FAILED, errorCode, safeMessage(exception), step.getStepKey());
        if (run.getAiTaskId() != null) {
            updateAiTaskTerminal(run.getAiTaskId(), STATUS_FAILED);
        }
        publish(requireRun(run.getId()), requireStep(step.getId()), null, null);
    }

    private void recoverRunningRun(Long runId) {
        AgentRunEntity run = requireRun(runId);
        if (!STATUS_RUNNING.equals(run.getRunStatus())) {
            return;
        }
        AgentCheckpointEntity checkpoint = checkpointMapper.findLatestByRunId(runId);
        if (checkpoint == null) {
            failRecoveredRun(run, ErrorCode.AGENT_CHECKPOINT_INVALID.name(), "运行恢复缺少 checkpoint");
            return;
        }
        try {
            state(checkpoint);
        } catch (BusinessException exception) {
            failRecoveredRun(run, exception.getErrorCode().name(), exception.getMessage());
            return;
        }
        markActiveStepTerminal(runId, STATUS_FAILED);
        int changed = runMapper.update(null, new UpdateWrapper<AgentRunEntity>()
                .eq("id", runId).eq("deleted", 0).eq("version", run.getVersion()).eq("run_status", STATUS_RUNNING)
                .set("run_status", STATUS_QUEUED).set("current_step_key", checkpoint.getNextStepKey())
                .set("version", run.getVersion() + 1));
        if (changed == 1) {
            eventPublisher.publishEvent(new AgentRunSubmittedEvent(runId));
        }
    }

    private void failRecoveredRun(AgentRunEntity run, String errorCode, String errorMessage) {
        updateRunStatus(run, STATUS_FAILED, errorCode, errorMessage, run.getCurrentStepKey());
        markActiveStepTerminal(run.getId(), STATUS_FAILED);
        if (run.getAiTaskId() != null) {
            updateAiTaskTerminal(run.getAiTaskId(), STATUS_FAILED);
        }
        publish(requireRun(run.getId()), null, null, null);
    }

    private AgentCheckpointEntity saveCheckpoint(AgentRunEntity run, AgentRunStepEntity step, AgentStepResult result) {
        AgentRunEntity latest = requireRun(run.getId());
        long sequence = (latest.getCheckpointSequence() == null ? 0L : latest.getCheckpointSequence()) + 1;
        Map<String, Object> checkpointState = result.checkpointState() == null ? Map.of() : result.checkpointState();
        String stateJson = json(checkpointState);
        int runChanged = runMapper.update(null, new UpdateWrapper<AgentRunEntity>()
                .eq("id", latest.getId()).eq("deleted", 0).eq("version", latest.getVersion())
                .eq("run_status", STATUS_RUNNING).set("checkpoint_sequence", sequence)
                .set("version", latest.getVersion() + 1));
        if (runChanged != 1) {
            throw conflict(ErrorCode.AGENT_RUN_STATE_CONFLICT, "写入 checkpoint 时状态已变化");
        }
        AgentCheckpointEntity checkpoint = new AgentCheckpointEntity();
        checkpoint.setRunId(run.getId());
        checkpoint.setStepId(step.getId());
        checkpoint.setSequenceId(sequence);
        checkpoint.setSchemaVersion(CHECKPOINT_SCHEMA_VERSION);
        checkpoint.setStepKey(step.getStepKey());
        checkpoint.setNextStepKey(result.nextStepKey());
        checkpoint.setCheckpointStatus(result.interruption() == null ? STATUS_RUNNING : STATUS_WAITING);
        checkpoint.setStateJson(stateJson);
        checkpoint.setStateHash(sha256(stateJson));
        checkpoint.setDeleted(0);
        checkpoint.setVersion(0);
        checkpointMapper.insert(checkpoint);
        return checkpoint;
    }

    private AgentInterruptionEntity createInterruption(
            AgentRunEntity run,
            AgentRunStepEntity step,
            AgentCheckpointEntity checkpoint,
            AgentInterruptionRequest request) {
        if (request.expiresAt() == null || request.expiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "人工确认必须提供未来的过期时间");
        }
        String token = newResumeToken();
        AgentInterruptionEntity interruption = new AgentInterruptionEntity();
        interruption.setRunId(run.getId());
        interruption.setCheckpointId(checkpoint.getId());
        interruption.setStepId(step.getId());
        interruption.setInterruptionType(request.interruptionType());
        interruption.setInterruptionStatus(INTERRUPTION_WAITING);
        interruption.setResumeTokenHash(sha256(token));
        interruption.setTokenVersion(1);
        interruption.setRequestJson(json(request.request()));
        interruption.setExpiresAt(request.expiresAt());
        interruption.setDeleted(0);
        interruption.setVersion(0);
        interruptionMapper.insert(interruption);
        eventPublisher.publishEvent(new AgentInterruptionTokenIssuedEvent(
                run.getId(), interruption.getId(), token, interruption.getTokenVersion()));
        return interruption;
    }

    private String newResumeToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void updateRunStatus(
            AgentRunEntity run,
            String status,
            String errorCode,
            String errorMessage,
            String currentStepKey) {
        int changed = runMapper.update(null, new UpdateWrapper<AgentRunEntity>()
                .eq("id", run.getId()).eq("deleted", 0).eq("version", run.getVersion())
                .eq("run_status", STATUS_RUNNING).set("run_status", status)
                .set("current_step_key", currentStepKey).set("error_code", errorCode)
                .set("error_message", errorMessage).set("version", run.getVersion() + 1));
        if (changed != 1) {
            throw conflict(ErrorCode.AGENT_RUN_STATE_CONFLICT, "Agent Run 状态已变化");
        }
    }

    private void markActiveStepTerminal(Long runId, String terminalStatus) {
        stepMapper.update(null, new UpdateWrapper<AgentRunStepEntity>().eq("run_id", runId).eq("deleted", 0)
                .eq("step_status", STATUS_RUNNING).set("step_status", terminalStatus)
                .set("finished_at", LocalDateTime.now())
                .set("error_code", "AGENT_RUN_" + terminalStatus.toUpperCase()));
    }

    private void terminateWaitingInterruptions(Long runId, String runStatus) {
        String interruptionStatus = STATUS_TIMED_OUT.equals(runStatus) ? "expired" : STATUS_CANCELED;
        interruptionMapper.update(null, new UpdateWrapper<AgentInterruptionEntity>()
                .eq("run_id", runId)
                .eq("deleted", 0)
                .eq("interruption_status", INTERRUPTION_WAITING)
                .set("interruption_status", interruptionStatus)
                .setSql("version = version + 1"));
    }

    private void markAiTaskRunning(Long taskId) {
        if (taskId != null) {
            taskMapper.update(null, new UpdateWrapper<AiTaskEntity>().eq("id", taskId).eq("deleted", 0)
                    .eq("task_status", STATUS_QUEUED).set("task_status", STATUS_RUNNING)
                    .setSql("version = version + 1"));
        }
    }

    private void updateAiTaskTerminal(Long taskId, String runStatus) {
        String taskStatus = STATUS_TIMED_OUT.equals(runStatus) ? STATUS_FAILED : runStatus;
        taskMapper.update(null, new UpdateWrapper<AiTaskEntity>().eq("id", taskId).eq("deleted", 0)
                .in("task_status", STATUS_QUEUED, STATUS_RUNNING).set("task_status", taskStatus)
                .set("error_code", STATUS_TIMED_OUT.equals(runStatus) ? ErrorCode.AGENT_RUN_TIMED_OUT.name() : null)
                .setSql("version = version + 1"));
    }

    private boolean isAiTaskCanceled(Long taskId) {
        AiTaskEntity task = taskMapper.selectById(taskId);
        return task != null && STATUS_CANCELED.equals(task.getTaskStatus());
    }

    private void validateOwnership(StartAgentRunCommand command) {
        WorkEntity work = workMapper.selectById(command.workId());
        if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        if (command.chapterId() != null) {
            ChapterEntity chapter = chapterMapper.selectById(command.chapterId());
            if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())
                    || !command.workId().equals(chapter.getWorkId())) {
                throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在或不属于作品");
            }
        }
        if (command.aiTaskId() != null) {
            AiTaskEntity task = taskMapper.selectById(command.aiTaskId());
            boolean isTaskMissingOrDeleted = task == null || Integer.valueOf(1).equals(task.getDeleted());
            boolean hasMismatchedTaskWork = task != null && !command.workId().equals(task.getWorkId());
            boolean hasMismatchedTaskChapter = task != null && command.chapterId() != null
                    && !command.chapterId().equals(task.getChapterId());
            if (isTaskMissingOrDeleted || hasMismatchedTaskWork || hasMismatchedTaskChapter) {
                throw new BusinessException(ErrorCode.AI_TASK_NOT_FOUND, "AI 任务不存在或归属不一致");
            }
            if (!Set.of(STATUS_QUEUED, STATUS_RUNNING).contains(task.getTaskStatus())) {
                throw conflict(ErrorCode.AI_TASK_STATE_CONFLICT, "AI 任务已进入终态，不能绑定 Agent Run");
            }
        }
    }

    private void requireStartCommand(StartAgentRunCommand command) {
        if (command == null || blank(command.userId()) || command.workId() == null
                || blank(command.workflowType()) || blank(command.idempotencyKey())
                || command.inputSnapshotVersion() == null || command.input() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent Run 创建参数不完整");
        }
    }

    private boolean isSameStartRequest(
            AgentRunEntity run,
            StartAgentRunCommand command,
            String inputHash) {
        return Objects.equals(run.getWorkId(), command.workId())
                && Objects.equals(run.getChapterId(), command.chapterId())
                && Objects.equals(run.getAiTaskId(), command.aiTaskId())
                && Objects.equals(run.getInputSnapshotVersion(), command.inputSnapshotVersion())
                && Objects.equals(run.getInputHash(), inputHash);
    }

    private AgentRunEntity requireRun(Long runId) {
        AgentRunEntity run = runId == null ? null : runMapper.selectById(runId);
        if (run == null || Integer.valueOf(1).equals(run.getDeleted())) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent Run 不存在");
        }
        return run;
    }

    private AgentRunStepEntity requireStep(Long stepId) {
        AgentRunStepEntity step = stepMapper.selectById(stepId);
        if (step == null || Integer.valueOf(1).equals(step.getDeleted())) {
            throw new BusinessException(ErrorCode.AGENT_STEP_NOT_FOUND, "Agent Step 不存在");
        }
        return step;
    }

    private AgentRunStepEntity latestStep(Long runId, String stepKey) {
        return stepMapper.selectOne(new LambdaQueryWrapper<AgentRunStepEntity>().eq(AgentRunStepEntity::getRunId, runId)
                .eq(AgentRunStepEntity::getStepKey, stepKey).eq(AgentRunStepEntity::getDeleted, 0)
                .orderByDesc(AgentRunStepEntity::getAttempt).last("LIMIT 1"));
    }

    private AgentRunView view(AgentRunEntity run) {
        AgentInterruptionEntity interruption = interruptionMapper.findLatestByRunId(run.getId());
        return new AgentRunView(
                run.getId(), run.getWorkflowType(), run.getRunStatus(), run.getWorkId(), run.getChapterId(),
                run.getAiTaskId(), run.getCurrentStepKey(), run.getCheckpointSequence(),
                interruption == null ? null : interruption.getId(),
                interruption == null ? null : interruption.getTokenVersion(), run.getTimeoutAt(),
                run.getErrorCode(), run.getErrorMessage());
    }

    private void publish(
            AgentRunEntity run,
            AgentRunStepEntity step,
            AgentCheckpointEntity checkpoint,
            Long interruptionId) {
        if (run.getChapterId() != null) {
            eventPublisher.publishEvent(AgentRunEvent.updated(run.getChapterId(), run.getId(), run.getWorkflowType(),
                    run.getAiTaskId(), run.getRunStatus(), step == null ? null : step.getId(),
                    step == null ? run.getCurrentStepKey() : step.getStepKey(),
                    step == null ? null : step.getStepStatus(),
                    checkpoint == null ? run.getCheckpointSequence() : checkpoint.getSequenceId(), interruptionId));
        }
    }

    private Map<String, Object> state(AgentCheckpointEntity checkpoint) {
        if (checkpoint.getSchemaVersion() == null || checkpoint.getSchemaVersion() != CHECKPOINT_SCHEMA_VERSION
                || !sha256(checkpoint.getStateJson()).equals(checkpoint.getStateHash())) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "checkpoint 格式或校验失败");
        }
        return map(checkpoint.getStateJson());
    }

    private Map<String, Object> humanResponse(AgentCheckpointEntity checkpoint) {
        if (checkpoint == null) {
            return Map.of();
        }
        AgentInterruptionEntity interruption = interruptionMapper.findLatestByRunId(checkpoint.getRunId());
        if (interruption == null || !INTERRUPTION_RESUMED.equals(interruption.getInterruptionStatus())
                || !checkpoint.getId().equals(interruption.getCheckpointId())) {
            return Map.of();
        }
        if (blank(interruption.getResponseJson()) || blank(interruption.getResponseHash())
                || !sha256(interruption.getResponseJson()).equals(interruption.getResponseHash())) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "人工恢复响应格式或校验失败");
        }
        return map(interruption.getResponseJson());
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AGENT_CHECKPOINT_INVALID, "checkpoint JSON 无法读取", exception);
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 状态无法序列化", exception);
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "Agent 步骤执行失败"
                : message.substring(0, Math.min(500, message.length()));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BusinessException conflict(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }

    private record PreparedStep(
            AgentRunEntity run,
            AgentRunStepEntity step,
            AgentCheckpointEntity checkpoint,
            AgentWorkflowDefinition definition,
            AgentStepExecutionContext context) {
    }
}
