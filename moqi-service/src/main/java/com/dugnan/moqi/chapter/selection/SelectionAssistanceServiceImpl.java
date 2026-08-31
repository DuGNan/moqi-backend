package com.dugnan.moqi.chapter.selection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
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
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.AcceptRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ContinueRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ConversationHistoryMessage;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.CreateRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ModelPlanningProposal;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.PlanningContext;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.PlanningChangePackageView;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.RetryRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.TextDiff;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.View;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.chapter.service.ProseObjectConversationService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.api.PublicFailure;
import com.dugnan.moqi.common.api.PublicFailureFactory;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 实现选区输入冻结、可恢复候选运行和严格乐观锁采纳。
 */
@Service
public class SelectionAssistanceServiceImpl implements SelectionAssistanceService {

    public static final String WORKFLOW_TYPE = "chapter_selection_assistance_v1";
    public static final String GENERATE_STEP = "generate_candidate";
    private static final String LOCAL_USER = "local-user";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_REVIEW_REQUIRED = "review_required";
    private static final String STATUS_FAILED = "failed";
    private static final String OPERATION_DISCUSS = "discuss";
    private static final String WORKFLOW_CO_CREATION = "co_creation";
    private static final String TARGET_FORMAL = "formal";
    private static final String TARGET_CANDIDATE = "candidate";
    private static final String SCOPE_SELECTION = "selection";
    private static final String SCOPE_WHOLE = "whole";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String CANDIDATE_PREFIX = "candidate:";
    private static final String FORMAL_PREFIX = "formal:";
    private static final int TARGET_CONTRACT_VERSION = 2;
    private static final int MAX_SELECTED_LENGTH = 100000;
    private static final int MAX_INSTRUCTION_LENGTH = 2000;
    private static final Set<String> OPERATIONS = Set.of(
            OPERATION_DISCUSS, "rewrite", "polish", "expand", "compress");
    private static final int ADJACENT_LIMIT = 800;
    private static final int MAX_HISTORY_MESSAGES = 24;

    private final ChapterMapper chapterMapper;
    private final ChapterSelectionAssistanceMapper assistanceMapper;
    private final AiTaskMapper taskMapper;
    private final ChapterGenerationBriefService briefService;
    private final ObjectMapper objectMapper;
    private AgentRuntime agentRuntime;
    private ChapterProseCandidateMapper candidateMapper;
    private ChapterGenerationMapper generationMapper;
    private ChapterConversationMapper conversationMapper;
    private ChapterConversationMessageMapper messageMapper;
    private ProsePlanningChangeService planningChangeService;
    private ProseObjectConversationService proseObjectConversationService;

    public SelectionAssistanceServiceImpl(
            ChapterMapper chapterMapper,
            ChapterSelectionAssistanceMapper assistanceMapper,
            AiTaskMapper taskMapper,
            @Lazy ChapterGenerationBriefService briefService,
            ObjectMapper objectMapper) {
        this.chapterMapper = chapterMapper;
        this.assistanceMapper = assistanceMapper;
        this.taskMapper = taskMapper;
        this.briefService = briefService;
        this.objectMapper = objectMapper;
    }

    @Autowired
    public void setAgentRuntime(@Lazy AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    public void setWorkspaceDependencies(
            ChapterProseCandidateMapper candidateMapper,
            ChapterGenerationMapper generationMapper,
            ChapterConversationMapper conversationMapper,
            ChapterConversationMessageMapper messageMapper,
            ProsePlanningChangeService planningChangeService) {
        setWorkspaceDependencies(candidateMapper, generationMapper, conversationMapper, messageMapper,
                planningChangeService, null);
    }

    @Autowired
    public void setWorkspaceDependencies(
            ChapterProseCandidateMapper candidateMapper,
            ChapterGenerationMapper generationMapper,
            ChapterConversationMapper conversationMapper,
            ChapterConversationMessageMapper messageMapper,
            ProsePlanningChangeService planningChangeService,
            ProseObjectConversationService proseObjectConversationService) {
        this.candidateMapper = candidateMapper;
        this.generationMapper = generationMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.planningChangeService = planningChangeService;
        this.proseObjectConversationService = proseObjectConversationService;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public View create(Long chapterId, CreateRequest request) {
        ChapterEntity chapter = requireChapter(chapterId);
        validateBasicRequest(request);
        String normalizedKey = request.idempotencyKey().trim();
        ChapterSelectionAssistanceEntity existing = findByIdempotency(chapterId, normalizedKey);
        if (existing != null) {
            if (!matchesRequest(existing, request)) {
                throw conflict("幂等键已经绑定不同的选区输入");
            }
            return view(existing);
        }
        ChapterProseCandidateEntity lockedCandidate = null;
        if (isTargetRequest(request)) {
            chapter = requireLockedChapter(chapterId);
            if (TARGET_CANDIDATE.equals(request.targetKind())) {
                lockedCandidate = requireLockedCandidate(chapterId, parseCandidateId(request.targetId()));
            }
            existing = findByIdempotency(chapterId, normalizedKey);
            if (existing != null) {
                if (!matchesRequest(existing, request)) {
                    throw conflict("幂等键已经绑定不同的选区输入");
                }
                return view(existing);
            }
        }
        ChapterSelectionAssistanceEntity parent = requireParent(chapter, request.parentId());
        Target target = resolveTarget(chapter, request, lockedCandidate);
        validateTarget(request, target);
        ChapterProseCandidateEntity createdCandidate = createFormalModificationCandidate(
                chapter, request, target, normalizedKey);
        if (createdCandidate != null) {
            target = candidateTarget(createdCandidate, target.scope(), target.selectionStart(),
                    target.selectionEnd(), target.selectedText());
        }
        GenerationBriefPreview brief = briefService.preview(chapterId, null);
        PlanningContext planningContext = OPERATION_DISCUSS.equals(request.operation())
                ? null : planningChangeService.freezeContext(chapterId);
        ConversationContext conversationContext = isTargetRequest(request)
                ? conversationContext(chapter, target) : null;
        String fingerprint = inputFingerprint(
                chapter, request, target, parent, brief, planningContext, conversationContext);
        return persistAssistanceRun(chapter, request, normalizedKey, parent, target,
                createdCandidate, brief, fingerprint, planningContext, conversationContext);
    }

    private View persistAssistanceRun(
            ChapterEntity chapter,
            CreateRequest request,
            String idempotencyKey,
            ChapterSelectionAssistanceEntity parent,
            Target target,
            ChapterProseCandidateEntity createdCandidate,
            GenerationBriefPreview brief,
            String fingerprint,
            PlanningContext planningContext,
            ConversationContext conversationContext) {
        AiTaskEntity task = createTask(chapter, request, fingerprint);
        ChapterSelectionAssistanceEntity entity = createAssistanceEntity(
                chapter, request, idempotencyKey, parent, target, createdCandidate, brief, fingerprint,
                planningContext, conversationContext, task.getId());
        if (conversationContext != null) {
            persistAssistanceUserMessage(chapter, entity, idempotencyKey);
        }
        assistanceMapper.insert(entity);
        startRun(chapter, idempotencyKey, task.getId(), entity);
        return get(entity.getId());
    }

    private AiTaskEntity createTask(ChapterEntity chapter, CreateRequest request, String fingerprint) {
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(WORKFLOW_TYPE);
        task.setTaskStatus(STATUS_QUEUED);
        task.setWorkId(chapter.getWorkId());
        task.setChapterId(chapter.getId());
        task.setTaskInputJson(json(Map.of("inputFingerprint", fingerprint, "operation", request.operation())));
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);
        return task;
    }

    private ChapterSelectionAssistanceEntity createAssistanceEntity(
            ChapterEntity chapter,
            CreateRequest request,
            String idempotencyKey,
            ChapterSelectionAssistanceEntity parent,
            Target target,
            ChapterProseCandidateEntity createdCandidate,
            GenerationBriefPreview brief,
            String fingerprint,
            PlanningContext planningContext,
            ConversationContext conversationContext,
            Long taskId) {
        ChapterSelectionAssistanceEntity entity = new ChapterSelectionAssistanceEntity();
        entity.setWorkId(chapter.getWorkId());
        entity.setChapterId(chapter.getId());
        entity.setParentId(parent == null ? null : parent.getId());
        entity.setAiTaskId(taskId);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setOperationType(request.operation());
        entity.setRequestStatus(STATUS_QUEUED);
        entity.setTargetKind(target.kind());
        entity.setRequestContractVersion(isTargetRequest(request) ? TARGET_CONTRACT_VERSION : 1);
        entity.setTargetObjectId(target.objectId());
        entity.setTargetCandidateId(target.candidateId());
        entity.setTargetContentVersion(target.version());
        entity.setTargetContentHash(target.contentHash());
        entity.setReferenceScope(target.scope());
        entity.setBaseChapterVersion(chapter.getVersion());
        entity.setBaseContentHash(target.contentHash());
        entity.setSelectionStart(target.selectionStart());
        entity.setSelectionEnd(target.selectionEnd());
        entity.setSelectedText(target.selectedText());
        entity.setReferenceTextHash(hash(target.selectedText()));
        entity.setReferenceSentenceCount(sentenceCount(target.selectedText()));
        entity.setReferenceSnapshot(target.selectedText());
        entity.setCreatedCandidateId(createdCandidate == null ? null : createdCandidate.getId());
        entity.setProposalStatus(OPERATION_DISCUSS.equals(request.operation()) ? "discussion" : "pending");
        entity.setAdjacentBefore(adjacentBefore(target.content(), target.selectionStart()));
        entity.setAdjacentAfter(adjacentAfter(target.content(), target.selectionEnd()));
        entity.setUserInstruction(normalizeInstruction(request.instruction()));
        entity.setBriefTemplateVersion(brief.templateVersion());
        entity.setBriefFingerprint(brief.fingerprint());
        entity.setBriefContent(brief.content());
        entity.setInputFingerprint(fingerprint);
        entity.setPlanningContextJson(planningContext == null ? null : json(planningContext));
        if (conversationContext != null) {
            entity.setConversationId(conversationContext.conversation().getId());
            entity.setConversationHistoryJson(json(conversationContext.history()));
        }
        entity.setDeleted(0);
        entity.setVersion(0);
        return entity;
    }

    private void startRun(
            ChapterEntity chapter,
            String idempotencyKey,
            Long taskId,
            ChapterSelectionAssistanceEntity entity) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("assistanceId", entity.getId());
        input.put("workId", chapter.getWorkId());
        input.put("chapterId", chapter.getId());
        input.put("aiTaskId", taskId);
        AgentRunView run = agentRuntime.start(new StartAgentRunCommand(LOCAL_USER, chapter.getWorkId(), chapter.getId(),
                WORKFLOW_TYPE, idempotencyKey, chapter.getVersion().longValue(), input, taskId));
        assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", entity.getId()).eq("version", 0)
                .set("agent_run_id", run.runId()).setSql("version = version + 1"));
    }

    @Override
    public View get(Long requestId) {
        return view(requireAssistance(requestId));
    }

    @Override
    public AgentRunView retry(Long requestId, RetryRequest request) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        if (!STATUS_FAILED.equals(entity.getRequestStatus()) || entity.getAgentRunId() == null) {
            throw conflict("只有失败的选区协助运行可以重试");
        }
        if (request == null || request.expectedAttempt() == null) {
            throw conflict("重试必须提交 expectedAttempt");
        }
        return agentRuntime.retryStep(new RetryAgentStepCommand(
                entity.getAgentRunId(), GENERATE_STEP, request.expectedAttempt()));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView cancel(Long requestId) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        if (entity.getAgentRunId() == null) {
            throw conflict("选区协助运行尚未建立");
        }
        AgentRunView run = agentRuntime.cancel(entity.getAgentRunId());
        assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", entity.getId()).in("request_status", STATUS_QUEUED, STATUS_RUNNING)
                .set("request_status", "canceled").set("proposal_status", "canceled")
                .setSql("version = version + 1"));
        updateTaskStatus(entity.getAiTaskId(), "canceled", null, null);
        return run;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public View reject(Long requestId) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        if (!Set.of(STATUS_READY, STATUS_REVIEW_REQUIRED).contains(entity.getRequestStatus())) {
            throw conflict("只有已完成候选可以拒绝");
        }
        int rejected = assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", entity.getId()).eq("version", entity.getVersion())
                .set("request_status", "rejected").set("proposal_status", "rejected")
                .setSql("version = version + 1"));
        if (rejected != 1) {
            throw conflict("候选状态已经变化，请刷新后重试");
        }
        return get(requestId);
    }

    @Override
    public View continueFrom(Long requestId, ContinueRequest request) {
        ChapterSelectionAssistanceEntity parent = requireAssistance(requestId);
        if (OPERATION_DISCUSS.equals(parent.getOperationType())
                || !Set.of(STATUS_READY, STATUS_REVIEW_REQUIRED).contains(parent.getRequestStatus())) {
            throw conflict("只有已完成的正文修改候选可以继续修改");
        }
        if (request == null) {
            throw badRequest("继续修改请求不能为空");
        }
        CreateRequest createRequest = Integer.valueOf(TARGET_CONTRACT_VERSION)
                .equals(parent.getRequestContractVersion())
                ? new CreateRequest(parent.getBaseChapterVersion(), parent.getBaseContentHash(),
                        parent.getSelectionStart(), parent.getSelectionEnd(), parent.getSelectedText(),
                        parent.getOperationType(), request.instruction(), parent.getId(), request.idempotencyKey(),
                        parent.getTargetKind(), parent.getTargetObjectId(), parent.getTargetContentVersion(),
                        parent.getReferenceScope())
                : new CreateRequest(parent.getBaseChapterVersion(), parent.getBaseContentHash(),
                        parent.getSelectionStart(), parent.getSelectionEnd(), parent.getSelectedText(),
                        parent.getOperationType(), request.instruction(), parent.getId(), request.idempotencyKey());
        return create(parent.getChapterId(), createRequest);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public View accept(Long requestId, AcceptRequest request) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        if (Integer.valueOf(TARGET_CONTRACT_VERSION).equals(entity.getRequestContractVersion())) {
            throw conflict("新正文工作区的修改提案只能在客户端应用并显式保存候选");
        }
        if (OPERATION_DISCUSS.equals(entity.getOperationType()) || !StringUtils.hasText(entity.getResultContent())
                || !Set.of(STATUS_READY, STATUS_REVIEW_REQUIRED).contains(entity.getRequestStatus())) {
            throw conflict("当前记录不是可采纳的正文修改候选");
        }
        if (request == null || request.baseVersion() == null || !StringUtils.hasText(request.contentHash())) {
            throw badRequest("采纳必须提交 baseVersion 和 contentHash");
        }
        ChapterEntity chapter = chapterMapper.selectByIdForUpdate(entity.getChapterId());
        if (chapter == null) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        if (!WORKFLOW_CO_CREATION.equals(chapter.getWorkflowStatus())) {
            throw conflict("已发布章节不能直接采纳局部修改，请先创建修订草稿");
        }
        if (!request.baseVersion().equals(entity.getBaseChapterVersion())
                || !request.baseVersion().equals(chapter.getVersion())
                || !request.contentHash().equals(entity.getBaseContentHash())
                || !request.contentHash().equals(hash(content(chapter)))) {
            throw new BusinessException(ErrorCode.CHAPTER_VERSION_CONFLICT, "章节正文已变化，请刷新后重新发起候选");
        }
        String current = content(chapter);
        if (!validRange(current, entity.getSelectionStart(), entity.getSelectionEnd())
                || !entity.getSelectedText().equals(current.substring(entity.getSelectionStart(), entity.getSelectionEnd()))) {
            throw new BusinessException(ErrorCode.CHAPTER_VERSION_CONFLICT, "选区原文已变化，请刷新后重新发起候选");
        }
        String updated = current.substring(0, entity.getSelectionStart()) + entity.getResultContent()
                + current.substring(entity.getSelectionEnd());
        if (chapterMapper.updateContentIfVersion(chapter.getId(), updated, chapter.getVersion()) != 1) {
            throw new BusinessException(ErrorCode.CHAPTER_VERSION_CONFLICT, "章节正文版本冲突");
        }
        int accepted = assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", entity.getId()).eq("version", entity.getVersion())
                .in("request_status", STATUS_READY, STATUS_REVIEW_REQUIRED)
                .set("request_status", "accepted").set("proposal_status", "accepted")
                .set("accepted_chapter_version", chapter.getVersion() + 1)
                .setSql("version = version + 1"));
        if (accepted != 1) {
            throw conflict("候选状态已变化，请刷新后重试");
        }
        return get(requestId);
    }

    @Override
    public PlanningChangePackageView getPlanningChangePackage(Long requestId) {
        return planningChangeService.getByAssistance(requestId);
    }

    /** 将候选标记为正在执行。 */
    @Transactional(rollbackFor = RuntimeException.class)
    public void markRunning(Long requestId) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        int updated = assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", requestId).in("request_status", STATUS_QUEUED, STATUS_FAILED)
                .set("request_status", STATUS_RUNNING)
                .set("proposal_status", OPERATION_DISCUSS.equals(entity.getOperationType())
                        ? "discussion" : "pending")
                .set("error_code", null).set("error_message", null)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw conflict("选区协助状态已经变化");
        }
        updateTaskStatus(entity.getAiTaskId(), STATUS_RUNNING, null, null);
    }

    /** 构造仅含选区、有限相邻段落、只读 Brief 和冻结规划的自然语言模型输入。 */
    public String modelPrompt(Long requestId) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        String sourceText = sourceText(entity);
        StringBuilder prompt = new StringBuilder();
        prompt.append("本轮任务：").append(operationInstruction(entity.getOperationType())).append('\n');
        prompt.append("作者要求：").append(entity.getUserInstruction()).append("\n\n");
        prompt.append("需要处理的正文：\n").append(sourceText).append("\n\n");
        prompt.append("正文前方的有限上下文：\n").append(entity.getAdjacentBefore()).append("\n\n");
        prompt.append("正文后方的有限上下文：\n").append(entity.getAdjacentAfter()).append("\n\n");
        prompt.append("以下是只读的章节生成 Brief，不能把其中候选信息当成作者确认：\n")
                .append(entity.getBriefContent()).append("\n\n");
        PlanningContext planningContext = readPlanningContext(entity);
        if (planningContext == null) {
            prompt.append("当前没有可用的权威场景规划，因此本轮不得返回规划提案。\n");
        } else {
            prompt.append("当前权威场景规划摘要（规划提案的 beforeSummary 必须原样复制这一行）：\n")
                    .append(planningContext.beforeSummary()).append("\n\n");
            prompt.append("当前完整场景规划 JSON；只有正文改写确实要求改变规划时，才返回修改后的完整 scenes：\n")
                    .append(json(planningContext.scenes())).append('\n');
        }
        prompt.append("所有正文和规划输出都只是候选，不得宣称已经保存、确认或发布。");
        return prompt.toString();
    }

    /** 返回创建 assistance 时冻结的当前正文对象会话历史。 */
    public List<ConversationHistoryMessage> modelHistory(Long requestId) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        if (!StringUtils.hasText(entity.getConversationHistoryJson())) {
            return List.of();
        }
        List<ConversationHistoryMessage> history = read(
                entity.getConversationHistoryJson(), new TypeReference<>() { });
        if (history == null) {
            return List.of();
        }
        for (ConversationHistoryMessage message : history) {
            if (message == null || !Set.of(ROLE_USER, ROLE_ASSISTANT).contains(message.role())
                    || !StringUtils.hasText(message.content())) {
                throw new IllegalStateException("正文对象会话历史快照不符合模型消息契约");
            }
        }
        return List.copyOf(history);
    }

    /** 返回模型调用的冻结来源指纹。 */
    public String sourceFingerprint(Long requestId) {
        return requireAssistance(requestId).getInputFingerprint();
    }

    /** 返回选区操作类型。 */
    public String operation(Long requestId) {
        return requireAssistance(requestId).getOperationType();
    }

    /** 持久化经过结构校验的模型候选。 */
    @Transactional(rollbackFor = RuntimeException.class)
    public void complete(
            Long requestId,
            String resultContent,
            String factRiskStatus,
            List<String> reasons,
            ModelPlanningProposal planningProposal,
            String modelCallRef) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        if (!StringUtils.hasText(resultContent) || resultContent.length() > MAX_SELECTED_LENGTH) {
            throw badRequest("模型结果为空或超过局部候选长度限制");
        }
        String normalizedRisk = planningProposal == null && "safe".equals(factRiskStatus)
                ? "safe" : STATUS_REVIEW_REQUIRED;
        List<String> safeReasons = reasons == null ? List.of() : reasons.stream()
                .filter(StringUtils::hasText).limit(20).map(value -> value.substring(0, Math.min(300, value.length()))).toList();
        String normalizedContent = resultContent.trim();
        String status = STATUS_REVIEW_REQUIRED.equals(normalizedRisk) ? STATUS_REVIEW_REQUIRED : STATUS_READY;
        String originalForDiff = sourceText(entity);
        String diff = OPERATION_DISCUSS.equals(entity.getOperationType()) ? null
                : json(new TextDiff(originalForDiff, normalizedContent,
                        originalForDiff.length(), normalizedContent.length()));
        if (planningProposal != null) {
            planningChangeService.createCandidate(requestId, planningProposal);
        }
        int updated = assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", requestId).eq("request_status", STATUS_RUNNING)
                .set("request_status", status).set("result_content", normalizedContent)
                .set("proposal_status", OPERATION_DISCUSS.equals(entity.getOperationType())
                        ? "discussion" : "ready")
                .set("diff_json", diff).set("fact_risk_status", normalizedRisk)
                .set("fact_risk_reasons_json", json(safeReasons)).set("model_call_ref", modelCallRef)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw conflict("选区协助已被取消或状态已经变化");
        }
        if (isTargetAssistance(entity)) {
            persistAssistanceAssistantMessage(entity, normalizedContent);
        }
        updateTaskStatus(entity.getAiTaskId(), "completed", null, null);
    }

    /** 收敛运行失败状态并只保存安全错误摘要。 */
    @Transactional(rollbackFor = RuntimeException.class)
    public void fail(Long requestId, String errorCode) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", requestId).notIn("request_status", "accepted", "rejected", "canceled")
                .set("request_status", STATUS_FAILED).set("proposal_status", STATUS_FAILED)
                .set("error_code", errorCode)
                .set("error_message", "选区协助模型调用失败，可按失败步骤重试")
                .setSql("version = version + 1"));
        updateTaskStatus(entity.getAiTaskId(), STATUS_FAILED, errorCode, "选区协助模型调用失败");
    }

    private void validateBasicRequest(CreateRequest request) {
        if (request == null || !StringUtils.hasText(request.contentHash())
                || !OPERATIONS.contains(request.operation())
                || !StringUtils.hasText(request.idempotencyKey()) || request.idempotencyKey().length() > 128) {
            throw badRequest("选区、操作、正文版本、正文哈希和 idempotencyKey 必须符合契约");
        }
        if (isTargetRequest(request)) {
            if (invalidTargetContract(request)) {
                throw badRequest("targetKind、targetId、targetVersion 和 referenceScope 不符合正文目标契约");
            }
        } else if (request.baseVersion() == null) {
            throw badRequest("旧请求必须提交 baseVersion");
        }
        if (missingSelectionReference(request)) {
            throw badRequest("选区引用必须提交偏移和原文");
        }
        if (request.selectedText() != null && request.selectedText().length() > MAX_SELECTED_LENGTH) {
            throw badRequest("选区原文超过局部候选长度限制");
        }
        if (request.instruction() != null && request.instruction().length() > MAX_INSTRUCTION_LENGTH) {
            throw badRequest("instruction 不能超过 2000 字符");
        }
    }

    private boolean matchesRequest(ChapterSelectionAssistanceEntity existing, CreateRequest request) {
        if (!matchesTargetIdentity(existing, request)) {
            return false;
        }
        Integer requestedVersion = isTargetRequest(request) ? request.targetVersion() : request.baseVersion();
        boolean formalClone = isTargetRequest(request) && TARGET_FORMAL.equals(request.targetKind())
                && existing.getCreatedCandidateId() != null;
        Integer existingVersion = !isTargetRequest(request) || formalClone
                ? existing.getBaseChapterVersion() : existing.getTargetContentVersion();
        return Objects.equals(existingVersion, requestedVersion)
                && Objects.equals(existing.getBaseContentHash(), request.contentHash())
                && (SCOPE_WHOLE.equals(request.referenceScope())
                || (Objects.equals(existing.getSelectionStart(), request.selectionStart())
                && Objects.equals(existing.getSelectionEnd(), request.selectionEnd())
                && Objects.equals(existing.getSelectedText(), request.selectedText())))
                && Objects.equals(existing.getOperationType(), request.operation())
                && Objects.equals(existing.getUserInstruction(), normalizeInstruction(request.instruction()))
                && Objects.equals(existing.getParentId(), request.parentId());
    }

    private boolean matchesTargetIdentity(
            ChapterSelectionAssistanceEntity existing,
            CreateRequest request) {
        if (!isTargetRequest(request)) {
            return !Integer.valueOf(TARGET_CONTRACT_VERSION).equals(existing.getRequestContractVersion());
        }
        if (!Integer.valueOf(TARGET_CONTRACT_VERSION).equals(existing.getRequestContractVersion())
                || !Objects.equals(existing.getReferenceScope(), normalizedScope(request.referenceScope()))) {
            return false;
        }
        if (existing.getCreatedCandidateId() != null) {
            return TARGET_FORMAL.equals(request.targetKind())
                    && Objects.equals(request.targetId(), FORMAL_PREFIX + existing.getChapterId())
                    && Objects.equals(existing.getTargetCandidateId(), existing.getCreatedCandidateId())
                    && Objects.equals(existing.getTargetObjectId(), candidateObjectId(existing.getCreatedCandidateId()));
        }
        return Objects.equals(existing.getTargetKind(), request.targetKind())
                && Objects.equals(existing.getTargetObjectId(), request.targetId());
    }

    private String normalizedScope(String scope) {
        return SCOPE_WHOLE.equals(scope) ? SCOPE_WHOLE : SCOPE_SELECTION;
    }

    private ChapterSelectionAssistanceEntity requireParent(ChapterEntity chapter, Long parentId) {
        if (parentId == null) {
            return null;
        }
        ChapterSelectionAssistanceEntity parent = requireAssistance(parentId);
        if (!chapter.getId().equals(parent.getChapterId()) || !chapter.getWorkId().equals(parent.getWorkId())
                || OPERATION_DISCUSS.equals(parent.getOperationType())
                || !Set.of(STATUS_READY, STATUS_REVIEW_REQUIRED).contains(parent.getRequestStatus())) {
            throw conflict("父候选不属于当前章节或不可继续修改");
        }
        return parent;
    }

    private String inputFingerprint(
            ChapterEntity chapter,
            CreateRequest request,
            Target target,
            ChapterSelectionAssistanceEntity parent,
            GenerationBriefPreview brief,
            PlanningContext planningContext,
            ConversationContext conversationContext) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("chapterId", chapter.getId());
        input.put("targetKind", target.kind());
        input.put("targetId", target.objectId());
        input.put("targetVersion", target.version());
        input.put("contentHash", target.contentHash());
        input.put("referenceScope", target.scope());
        input.put("selectionStart", target.selectionStart());
        input.put("selectionEnd", target.selectionEnd());
        input.put("selectedText", target.selectedText());
        input.put("operation", request.operation());
        input.put("instruction", normalizeInstruction(request.instruction()));
        input.put("parentId", parent == null ? null : parent.getId());
        input.put("parentResult", parent == null ? null : parent.getResultContent());
        input.put("briefFingerprint", brief.fingerprint());
        input.put("planningContext", planningContext);
        input.put("conversationHistory", conversationContext == null ? List.of() : conversationContext.history());
        return hash(json(input));
    }

    private String operationInstruction(String operation) {
        return switch (operation) {
            case OPERATION_DISCUSS -> "分析作者引用的正文并给出建议，不生成替换正文";
            case "rewrite" -> "按作者要求重写正文";
            case "polish" -> "润色正文，但不擅自改变情节事实";
            case "expand" -> "扩写正文，并保持既有事实边界";
            case "compress" -> "压缩正文，同时保留必要信息";
            default -> throw new IllegalStateException("正文协助操作缺少自然语言映射");
        };
    }

    private PlanningContext readPlanningContext(ChapterSelectionAssistanceEntity entity) {
        if (!StringUtils.hasText(entity.getPlanningContextJson())) {
            return null;
        }
        return read(entity.getPlanningContextJson(), new TypeReference<>() { });
    }

    private Target resolveTarget(
            ChapterEntity chapter,
            CreateRequest request,
            ChapterProseCandidateEntity lockedCandidate) {
        if (!isTargetRequest(request)) {
            String chapterContent = content(chapter);
            if (!Objects.equals(request.baseVersion(), chapter.getVersion())
                    || !Objects.equals(request.contentHash(), hash(chapterContent))) {
                throw new BusinessException(ErrorCode.CHAPTER_VERSION_CONFLICT, "章节正文版本或哈希已变化");
            }
            return rangedTarget(TARGET_FORMAL, FORMAL_PREFIX + chapter.getId(), null, chapter.getVersion(),
                    chapterContent, request);
        }
        if (TARGET_FORMAL.equals(request.targetKind())) {
            String chapterContent = content(chapter);
            if (!Objects.equals(request.targetId(), FORMAL_PREFIX + chapter.getId())
                    || !Objects.equals(request.targetVersion(), chapter.getVersion())
                    || !Objects.equals(request.contentHash(), hash(chapterContent))) {
                throw new BusinessException(ErrorCode.CHAPTER_VERSION_CONFLICT, "正式正文目标版本或哈希已变化");
            }
            return rangedTarget(TARGET_FORMAL, request.targetId(), null, chapter.getVersion(), chapterContent, request);
        }
        Long candidateId = parseCandidateId(request.targetId());
        ChapterProseCandidateEntity candidate = lockedCandidate == null
                ? requireCandidate(chapter.getId(), candidateId) : lockedCandidate;
        if (!Objects.equals(request.targetVersion(), candidate.getVersion())
                || !Objects.equals(request.contentHash(), candidate.getContentHash())) {
            throw new BusinessException(ErrorCode.PROSE_CANDIDATE_CONFLICT, "正文候选目标版本或哈希已变化");
        }
        return rangedTarget(TARGET_CANDIDATE, request.targetId(), candidateId, candidate.getVersion(),
                candidate.getContent(), request);
    }

    private ChapterProseCandidateEntity requireLockedCandidate(Long chapterId, Long candidateId) {
        ChapterProseCandidateEntity candidate = candidateMapper.selectByIdForUpdate(chapterId, candidateId);
        if (candidate == null) {
            throw new BusinessException(ErrorCode.PROSE_CANDIDATE_NOT_FOUND, "正文候选不存在");
        }
        return candidate;
    }

    private Target rangedTarget(
            String kind,
            String objectId,
            Long candidateId,
            Integer version,
            String targetContent,
            CreateRequest request) {
        String scope = SCOPE_WHOLE.equals(request.referenceScope()) ? SCOPE_WHOLE : SCOPE_SELECTION;
        int start = SCOPE_WHOLE.equals(scope) ? 0 : request.selectionStart();
        int end = SCOPE_WHOLE.equals(scope) ? targetContent.length() : request.selectionEnd();
        String selected = SCOPE_WHOLE.equals(scope) ? targetContent : request.selectedText();
        if (!validRange(targetContent, start, end)
                || !Objects.equals(selected, targetContent.substring(start, end))) {
            throw badRequest("引用范围、偏移与目标正文不匹配");
        }
        if (selected.length() > MAX_SELECTED_LENGTH) {
            throw badRequest("引用正文超过协助长度限制");
        }
        return new Target(kind, objectId, candidateId, version, hash(targetContent), targetContent,
                scope, start, end, selected);
    }

    private void validateTarget(CreateRequest request, Target target) {
        if (!Objects.equals(request.contentHash(), target.contentHash())) {
            throw new BusinessException(ErrorCode.CHAPTER_VERSION_CONFLICT, "目标正文哈希已变化");
        }
        if (!isTargetRequest(request) && !OPERATION_DISCUSS.equals(request.operation())) {
            ChapterEntity chapter = requireChapter(Long.valueOf(target.objectId().substring(FORMAL_PREFIX.length())));
            if (!WORKFLOW_CO_CREATION.equals(chapter.getWorkflowStatus())) {
                throw conflict("已发布章节只允许讨论，局部修改需等待修订草稿");
            }
        }
    }

    private ChapterProseCandidateEntity createFormalModificationCandidate(
            ChapterEntity chapter,
            CreateRequest request,
            Target target,
            String idempotencyKey) {
        if (!isTargetRequest(request) || !TARGET_FORMAL.equals(target.kind())
                || OPERATION_DISCUSS.equals(request.operation())) {
            return null;
        }
        ChapterGenerationEntity formalBasisSource = formalBasisSource(chapter);
        ChapterGenerationEntity source = new ChapterGenerationEntity();
        source.setWorkId(chapter.getWorkId());
        source.setChapterId(chapter.getId());
        if (formalBasisSource != null) {
            source.setBriefId(formalBasisSource.getBriefId());
            source.setOutlineId(formalBasisSource.getOutlineId());
            source.setOutlineRevision(formalBasisSource.getOutlineRevision());
            source.setChapterPlanVersionId(formalBasisSource.getChapterPlanVersionId());
            source.setBaseGenerationId(formalBasisSource.getId());
            source.setBasisSnapshotJson(formalBasisSource.getBasisSnapshotJson());
        }
        source.setGenerationStatus("candidate_snapshot");
        source.setGenerationMode("assistance");
        source.setSelectionMode("all");
        source.setIdempotencyKey("formal-assistance:" + hash(idempotencyKey));
        source.setGeneratedContent(target.content());
        source.setContentAssemblyMode("formal_assistance");
        source.setGenerationTemplateVersion("formal-assistance-v1");
        source.setGenerationFinishReason("candidate_created");
        source.setWordCount(target.content().codePointCount(0, target.content().length()));
        source.setValidityStatus("current");
        source.setDeleted(0);
        source.setVersion(0);
        generationMapper.insert(source);

        ChapterProseCandidateEntity candidate = new ChapterProseCandidateEntity();
        candidate.setWorkId(chapter.getWorkId());
        candidate.setChapterId(chapter.getId());
        candidate.setSourceKind("formal_assistance");
        candidate.setSourceGenerationId(source.getId());
        candidate.setQualityGenerationId(source.getId());
        candidate.setQualityRequestStatus("unavailable");
        candidate.setCandidateStatus("active");
        candidate.setAdoptionStatus("unadopted");
        candidate.setContent(target.content());
        candidate.setContentHash(target.contentHash());
        candidate.setWordCount(target.content().codePointCount(0, target.content().length()));
        candidate.setDeleted(0);
        candidate.setVersion(0);
        candidateMapper.insert(candidate);
        candidate.setRootCandidateId(candidate.getId());
        int rooted = candidateMapper.update(null, new UpdateWrapper<ChapterProseCandidateEntity>()
                .eq("id", candidate.getId()).isNull("root_candidate_id")
                .set("root_candidate_id", candidate.getId()));
        if (rooted != 1) {
            throw conflict("正式正文修改候选初始化失败");
        }
        return candidate;
    }

    private ChapterGenerationEntity formalBasisSource(ChapterEntity chapter) {
        if (chapter.getFormalSourceGenerationId() == null) {
            return null;
        }
        ChapterGenerationEntity source = generationMapper.selectById(chapter.getFormalSourceGenerationId());
        if (source == null || Integer.valueOf(1).equals(source.getDeleted())
                || !Objects.equals(source.getWorkId(), chapter.getWorkId())
                || !Objects.equals(source.getChapterId(), chapter.getId())) {
            throw conflict("正式正文的生成依据来源不存在或归属不一致");
        }
        return source;
    }

    private Target candidateTarget(
            ChapterProseCandidateEntity candidate,
            String scope,
            Integer selectionStart,
            Integer selectionEnd,
            String selectedText) {
        return new Target(TARGET_CANDIDATE, CANDIDATE_PREFIX + candidate.getId(), candidate.getId(),
                candidate.getVersion(), candidate.getContentHash(), candidate.getContent(), scope,
                selectionStart, selectionEnd, selectedText);
    }

    private void persistAssistanceUserMessage(
            ChapterEntity chapter,
            ChapterSelectionAssistanceEntity assistance,
            String idempotencyKey) {
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setConversationId(assistance.getConversationId());
        message.setChapterId(chapter.getId());
        message.setMessageRole(ROLE_USER);
        message.setContent(conversationUserContent(assistance));
        message.setClientMessageId("selection-assistance-user:" + hash(idempotencyKey));
        message.setGenerationStatus("completed");
        message.setDeleted(0);
        message.setVersion(0);
        messageMapper.insert(message);
        assistance.setUserMessageId(message.getId());
    }

    private ConversationContext conversationContext(ChapterEntity chapter, Target target) {
        if (proseObjectConversationService == null) {
            throw new IllegalStateException("正文对象会话服务不可用");
        }
        var detail = proseObjectConversationService.createOrGet(chapter.getId(), target.objectId());
        ChapterConversationEntity conversation = conversationMapper.selectById(detail.id());
        if (conversation == null || !chapter.getId().equals(conversation.getChapterId())
                || !target.objectId().equals(conversation.getTargetObjectId())) {
            throw new IllegalStateException("正文对象会话无法读取");
        }
        return new ConversationContext(conversation, freezeConversationHistory(conversation.getId()));
    }

    private List<ConversationHistoryMessage> freezeConversationHistory(Long conversationId) {
        List<ChapterConversationMessageEntity> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ChapterConversationMessageEntity>()
                        .eq(ChapterConversationMessageEntity::getConversationId, conversationId)
                        .eq(ChapterConversationMessageEntity::getDeleted, 0)
                        .in(ChapterConversationMessageEntity::getMessageRole, ROLE_USER, ROLE_ASSISTANT)
                        .orderByAsc(ChapterConversationMessageEntity::getGmtCreate)
                        .orderByAsc(ChapterConversationMessageEntity::getId));
        List<ConversationHistoryMessage> available = messages.stream()
                .filter(message -> StringUtils.hasText(message.getContent()))
                .map(message -> new ConversationHistoryMessage(message.getMessageRole(), message.getContent()))
                .toList();
        int start = Math.max(0, available.size() - MAX_HISTORY_MESSAGES);
        while (start < available.size() && !ROLE_USER.equals(available.get(start).role())) {
            start++;
        }
        return List.copyOf(available.subList(start, available.size()));
    }

    private String conversationUserContent(ChapterSelectionAssistanceEntity assistance) {
        String instruction = StringUtils.hasText(assistance.getUserInstruction())
                ? assistance.getUserInstruction() : "未补充额外要求";
        return operationLabel(assistance.getOperationType())
                + "\n作者要求：" + instruction
                + "\n引用正文：\n" + assistance.getReferenceSnapshot();
    }

    private void persistAssistanceAssistantMessage(
            ChapterSelectionAssistanceEntity assistance,
            String resultContent) {
        if (assistance.getConversationId() == null) {
            return;
        }
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setConversationId(assistance.getConversationId());
        message.setChapterId(assistance.getChapterId());
        message.setMessageRole(ROLE_ASSISTANT);
        message.setContent(OPERATION_DISCUSS.equals(assistance.getOperationType())
                ? resultContent
                : "已生成" + operationLabel(assistance.getOperationType())
                        + "提案，以下内容尚未应用或保存：\n" + resultContent);
        message.setClientMessageId("selection-assistance-assistant:" + assistance.getId());
        message.setGenerationStatus("completed");
        message.setAiTaskId(assistance.getAiTaskId());
        message.setDeleted(0);
        message.setVersion(0);
        messageMapper.insert(message);
        assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", assistance.getId()).isNull("assistant_message_id")
                .set("assistant_message_id", message.getId()));
    }

    private String operationLabel(String operation) {
        return switch (operation) {
            case OPERATION_DISCUSS -> "讨论选区";
            case "rewrite" -> "改写";
            case "polish" -> "润色";
            case "expand" -> "扩写";
            case "compress" -> "压缩";
            default -> throw new IllegalStateException("正文协助操作缺少作者可读名称");
        };
    }

    private ChapterProseCandidateEntity requireCandidate(Long chapterId, Long candidateId) {
        ChapterProseCandidateEntity candidate = candidateId == null ? null : candidateMapper.selectOne(
                new LambdaQueryWrapper<ChapterProseCandidateEntity>()
                        .eq(ChapterProseCandidateEntity::getId, candidateId)
                        .eq(ChapterProseCandidateEntity::getChapterId, chapterId)
                        .eq(ChapterProseCandidateEntity::getDeleted, 0));
        if (candidate == null) {
            throw new BusinessException(ErrorCode.PROSE_CANDIDATE_NOT_FOUND, "正文候选不存在");
        }
        return candidate;
    }

    private Long parseCandidateId(String objectId) {
        try {
            if (objectId == null || !objectId.startsWith(CANDIDATE_PREFIX)) {
                throw new NumberFormatException();
            }
            return Long.valueOf(objectId.substring(CANDIDATE_PREFIX.length()));
        } catch (NumberFormatException exception) {
            throw badRequest("候选目标 ID 格式不正确");
        }
    }

    private boolean isTargetRequest(CreateRequest request) {
        return request != null && (StringUtils.hasText(request.targetKind())
                || StringUtils.hasText(request.targetId())
                || request.targetVersion() != null
                || StringUtils.hasText(request.referenceScope()));
    }

    private boolean invalidTargetContract(CreateRequest request) {
        if (!StringUtils.hasText(request.targetKind())
                || !Set.of(TARGET_FORMAL, TARGET_CANDIDATE).contains(request.targetKind())) {
            return true;
        }
        if (!StringUtils.hasText(request.targetId()) || request.targetVersion() == null) {
            return true;
        }
        return request.referenceScope() != null
                && !Set.of(SCOPE_SELECTION, SCOPE_WHOLE).contains(request.referenceScope());
    }

    private boolean missingSelectionReference(CreateRequest request) {
        if (SCOPE_WHOLE.equals(request.referenceScope())) {
            return false;
        }
        return request.selectionStart() == null
                || request.selectionEnd() == null
                || request.selectedText() == null;
    }

    private boolean isTargetAssistance(ChapterSelectionAssistanceEntity entity) {
        return Integer.valueOf(TARGET_CONTRACT_VERSION).equals(entity.getRequestContractVersion())
                && entity.getConversationId() != null;
    }

    private int sentenceCount(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '。' || character == '！' || character == '？' || character == '\n') {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private ChapterEntity requireLockedChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectByIdForUpdate(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private ChapterSelectionAssistanceEntity requireAssistance(Long requestId) {
        ChapterSelectionAssistanceEntity entity = requestId == null ? null : assistanceMapper.selectById(requestId);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "选区协助记录不存在");
        }
        return entity;
    }

    private ChapterSelectionAssistanceEntity findByIdempotency(Long chapterId, String idempotencyKey) {
        return assistanceMapper.selectOne(new LambdaQueryWrapper<ChapterSelectionAssistanceEntity>()
                .eq(ChapterSelectionAssistanceEntity::getChapterId, chapterId)
                .eq(ChapterSelectionAssistanceEntity::getIdempotencyKey, idempotencyKey)
                .eq(ChapterSelectionAssistanceEntity::getDeleted, 0));
    }

    private View view(ChapterSelectionAssistanceEntity entity) {
        TextDiff diff = read(entity.getDiffJson(), new TypeReference<>() { });
        List<String> reasons = read(entity.getFactRiskReasonsJson(), new TypeReference<>() { });
        boolean canAccept = !Integer.valueOf(TARGET_CONTRACT_VERSION).equals(entity.getRequestContractVersion())
                && !OPERATION_DISCUSS.equals(entity.getOperationType())
                && Set.of(STATUS_READY, STATUS_REVIEW_REQUIRED).contains(entity.getRequestStatus());
        PlanningChangePackageView planningPackage = planningChangeService == null
                ? null : planningChangeService.getByAssistance(entity.getId());
        return new View(entity.getId(), entity.getWorkId(), entity.getChapterId(), entity.getParentId(),
                entity.getAiTaskId(), entity.getAgentRunId(), entity.getOperationType(), entity.getRequestStatus(),
                entity.getBaseChapterVersion(), entity.getBaseContentHash(), entity.getSelectionStart(),
                entity.getSelectionEnd(), entity.getSelectedText(), entity.getUserInstruction(),
                entity.getBriefTemplateVersion(), entity.getBriefFingerprint(), entity.getInputFingerprint(),
                entity.getResultContent(), diff, entity.getFactRiskStatus(), reasons == null ? List.of() : reasons,
                canAccept, STATUS_REVIEW_REQUIRED.equals(entity.getFactRiskStatus())
                        ? "如需改变故事事实，请提交为规划变更并重新生成候选" : null,
                entity.getErrorCode(), safeErrorMessage(entity.getErrorCode(), entity.getErrorMessage()),
                entity.getAcceptedChapterVersion(), entity.getVersion(), entity.getGmtCreate(), entity.getGmtModified(),
                publicFailure(entity.getAiTaskId(), entity.getErrorCode()), entity.getTargetKind(),
                entity.getTargetObjectId(), entity.getTargetContentVersion(), entity.getTargetContentHash(),
                entity.getReferenceScope(), entity.getReferenceTextHash(), entity.getReferenceSentenceCount(),
                referenceStale(entity), entity.getCreatedCandidateId(), candidateObjectId(entity.getCreatedCandidateId()),
                entity.getProposalStatus(), entity.getAppliedCandidateVersion(), entity.getAppliedCandidateHash(),
                entity.getConversationId(), entity.getUserMessageId(),
                entity.getAssistantMessageId(), planningPackage == null ? null : planningPackage.id());
    }

    private boolean referenceStale(ChapterSelectionAssistanceEntity entity) {
        if (!Integer.valueOf(TARGET_CONTRACT_VERSION).equals(entity.getRequestContractVersion())) {
            return false;
        }
        if (TARGET_CANDIDATE.equals(entity.getTargetKind())) {
            ChapterProseCandidateEntity candidate = entity.getTargetCandidateId() == null
                    ? null : candidateMapper.selectById(entity.getTargetCandidateId());
            return candidate == null || Integer.valueOf(1).equals(candidate.getDeleted())
                    || !Objects.equals(candidate.getVersion(), entity.getTargetContentVersion())
                    || !Objects.equals(candidate.getContentHash(), entity.getTargetContentHash());
        }
        ChapterEntity chapter = chapterMapper.selectById(entity.getChapterId());
        return chapter == null || !Objects.equals(chapter.getVersion(), entity.getTargetContentVersion())
                || !Objects.equals(hash(content(chapter)), entity.getTargetContentHash());
    }

    private String candidateObjectId(Long candidateId) {
        return candidateId == null ? null : CANDIDATE_PREFIX + candidateId;
    }

    private PublicFailure publicFailure(Long taskId, String errorCode) {
        if (!StringUtils.hasText(errorCode)) {
            return null;
        }
        AiTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
        return PublicFailureFactory.from(errorCode, task == null ? null : task.getDiagnosticRef());
    }

    private String safeErrorMessage(String errorCode, String errorMessage) {
        return StringUtils.hasText(errorCode) ? PublicFailureFactory.safeMessage(errorCode, errorMessage) : null;
    }

    private String sourceText(ChapterSelectionAssistanceEntity entity) {
        if (entity.getParentId() == null) {
            return entity.getSelectedText();
        }
        ChapterSelectionAssistanceEntity parent = requireAssistance(entity.getParentId());
        if (!StringUtils.hasText(parent.getResultContent())) {
            throw conflict("父候选正文不存在");
        }
        return parent.getResultContent();
    }

    private void updateTaskStatus(Long taskId, String status, String errorCode, String errorMessage) {
        AiTaskEntity task = taskMapper.selectById(taskId);
        boolean failed = "failed".equals(status);
        String diagnosticRef = task != null && StringUtils.hasText(task.getDiagnosticRef())
                ? task.getDiagnosticRef()
                : failed ? PublicFailureFactory.newDiagnosticRef() : null;
        taskMapper.update(null, new UpdateWrapper<AiTaskEntity>().eq("id", taskId)
                .set("task_status", status).set("error_code", errorCode)
                .set("error_message", PublicFailureFactory.safeMessage(errorCode, errorMessage))
                .set(failed, "diagnostic_ref", diagnosticRef)
                .setSql("version = version + 1"));
    }

    private String adjacentBefore(String content, int start) {
        return content.substring(Math.max(0, start - ADJACENT_LIMIT), start);
    }

    private String adjacentAfter(String content, int end) {
        return content.substring(end, Math.min(content.length(), end + ADJACENT_LIMIT));
    }

    private boolean validRange(String content, int start, int end) {
        return start >= 0 && end > start && end <= content.length();
    }

    private String content(ChapterEntity chapter) {
        return chapter.getContent() == null ? "" : chapter.getContent();
    }

    private String normalizeInstruction(String value) {
        return value == null ? "" : value.trim();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "选区协助数据无法序列化", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "选区协助结果无法读取", exception);
        }
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算选区协助输入指纹", exception);
        }
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CHAPTER_VERSION_CONFLICT, message);
    }

    private record Target(
            String kind,
            String objectId,
            Long candidateId,
            Integer version,
            String contentHash,
            String content,
            String scope,
            Integer selectionStart,
            Integer selectionEnd,
            String selectedText) {
    }

    private record ConversationContext(
            ChapterConversationEntity conversation,
            List<ConversationHistoryMessage> history) {
    }
}
