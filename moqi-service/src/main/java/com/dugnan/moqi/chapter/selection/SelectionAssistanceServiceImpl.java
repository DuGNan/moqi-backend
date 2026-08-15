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
import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.AcceptRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ContinueRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.CreateRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.RetryRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.TextDiff;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.View;
import com.dugnan.moqi.chapter.service.ChapterGenerationBriefService;
import com.dugnan.moqi.common.api.ErrorCode;
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
    private static final int MAX_SELECTED_LENGTH = 20000;
    private static final int MAX_INSTRUCTION_LENGTH = 2000;
    private static final Set<String> OPERATIONS = Set.of(
            OPERATION_DISCUSS, "rewrite", "polish", "expand", "compress");
    private static final int ADJACENT_LIMIT = 800;

    private final ChapterMapper chapterMapper;
    private final ChapterSelectionAssistanceMapper assistanceMapper;
    private final AiTaskMapper taskMapper;
    private final ChapterGenerationBriefService briefService;
    private final ObjectMapper objectMapper;
    private AgentRuntime agentRuntime;

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
        ChapterSelectionAssistanceEntity parent = requireParent(chapter, request.parentId());
        validateCurrentChapter(chapter, request);
        GenerationBriefPreview brief = briefService.preview(chapterId, null);
        String fingerprint = inputFingerprint(chapter, request, parent, brief);

        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(WORKFLOW_TYPE);
        task.setTaskStatus(STATUS_QUEUED);
        task.setWorkId(chapter.getWorkId());
        task.setChapterId(chapterId);
        task.setTaskInputJson(json(Map.of("inputFingerprint", fingerprint, "operation", request.operation())));
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);

        ChapterSelectionAssistanceEntity entity = new ChapterSelectionAssistanceEntity();
        entity.setWorkId(chapter.getWorkId());
        entity.setChapterId(chapterId);
        entity.setParentId(parent == null ? null : parent.getId());
        entity.setAiTaskId(task.getId());
        entity.setIdempotencyKey(normalizedKey);
        entity.setOperationType(request.operation());
        entity.setRequestStatus(STATUS_QUEUED);
        entity.setBaseChapterVersion(chapter.getVersion());
        entity.setBaseContentHash(request.contentHash());
        entity.setSelectionStart(request.selectionStart());
        entity.setSelectionEnd(request.selectionEnd());
        entity.setSelectedText(request.selectedText());
        entity.setAdjacentBefore(adjacentBefore(chapter.getContent(), request.selectionStart()));
        entity.setAdjacentAfter(adjacentAfter(chapter.getContent(), request.selectionEnd()));
        entity.setUserInstruction(normalizeInstruction(request.instruction()));
        entity.setBriefTemplateVersion(brief.templateVersion());
        entity.setBriefFingerprint(brief.fingerprint());
        entity.setBriefContent(brief.content());
        entity.setInputFingerprint(fingerprint);
        entity.setDeleted(0);
        entity.setVersion(0);
        assistanceMapper.insert(entity);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("assistanceId", entity.getId());
        input.put("workId", chapter.getWorkId());
        input.put("chapterId", chapterId);
        input.put("aiTaskId", task.getId());
        AgentRunView run = agentRuntime.start(new StartAgentRunCommand(LOCAL_USER, chapter.getWorkId(), chapterId,
                WORKFLOW_TYPE, normalizedKey, chapter.getVersion().longValue(), input, task.getId()));
        assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", entity.getId()).eq("version", 0)
                .set("agent_run_id", run.runId()).setSql("version = version + 1"));
        return get(entity.getId());
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
                .set("request_status", "canceled").setSql("version = version + 1"));
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
                .set("request_status", "rejected").setSql("version = version + 1"));
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
        CreateRequest createRequest = new CreateRequest(parent.getBaseChapterVersion(), parent.getBaseContentHash(),
                parent.getSelectionStart(), parent.getSelectionEnd(), parent.getSelectedText(), parent.getOperationType(),
                request.instruction(), parent.getId(), request.idempotencyKey());
        return create(parent.getChapterId(), createRequest);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public View accept(Long requestId, AcceptRequest request) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
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
                .set("request_status", "accepted").set("accepted_chapter_version", chapter.getVersion() + 1)
                .setSql("version = version + 1"));
        if (accepted != 1) {
            throw conflict("候选状态已变化，请刷新后重试");
        }
        return get(requestId);
    }

    /** 将候选标记为正在执行。 */
    @Transactional(rollbackFor = RuntimeException.class)
    public void markRunning(Long requestId) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        int updated = assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", requestId).in("request_status", STATUS_QUEUED, STATUS_FAILED)
                .set("request_status", STATUS_RUNNING).set("error_code", null).set("error_message", null)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw conflict("选区协助状态已经变化");
        }
        updateTaskStatus(entity.getAiTaskId(), STATUS_RUNNING, null, null);
    }

    /** 构造仅含选区、有限相邻段落和只读 Brief 的模型输入。 */
    public Map<String, Object> modelInput(Long requestId) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        String sourceText = sourceText(entity);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", entity.getOperationType());
        result.put("selectedText", sourceText);
        result.put("adjacentBefore", entity.getAdjacentBefore());
        result.put("adjacentAfter", entity.getAdjacentAfter());
        result.put("instruction", entity.getUserInstruction());
        result.put("chapterGenerationBrief", entity.getBriefContent());
        result.put("candidateBoundary", "模型结果仅为候选，不得确认、发布或更新故事事实");
        return result;
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
    public void complete(Long requestId, String resultContent, String factRiskStatus, List<String> reasons,
            String modelCallRef) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        if (!StringUtils.hasText(resultContent) || resultContent.length() > MAX_SELECTED_LENGTH) {
            throw badRequest("模型结果为空或超过局部候选长度限制");
        }
        String normalizedRisk = "safe".equals(factRiskStatus) ? "safe" : STATUS_REVIEW_REQUIRED;
        List<String> safeReasons = reasons == null ? List.of() : reasons.stream()
                .filter(StringUtils::hasText).limit(20).map(value -> value.substring(0, Math.min(300, value.length()))).toList();
        String normalizedContent = resultContent.trim();
        String status = STATUS_REVIEW_REQUIRED.equals(normalizedRisk) ? STATUS_REVIEW_REQUIRED : STATUS_READY;
        String originalForDiff = sourceText(entity);
        String diff = OPERATION_DISCUSS.equals(entity.getOperationType()) ? null
                : json(new TextDiff(originalForDiff, normalizedContent,
                        originalForDiff.length(), normalizedContent.length()));
        int updated = assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", requestId).eq("request_status", STATUS_RUNNING)
                .set("request_status", status).set("result_content", normalizedContent)
                .set("diff_json", diff).set("fact_risk_status", normalizedRisk)
                .set("fact_risk_reasons_json", json(safeReasons)).set("model_call_ref", modelCallRef)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw conflict("选区协助已被取消或状态已经变化");
        }
        updateTaskStatus(entity.getAiTaskId(), "completed", null, null);
    }

    /** 收敛运行失败状态并只保存安全错误摘要。 */
    @Transactional(rollbackFor = RuntimeException.class)
    public void fail(Long requestId, String errorCode) {
        ChapterSelectionAssistanceEntity entity = requireAssistance(requestId);
        assistanceMapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                .eq("id", requestId).notIn("request_status", "accepted", "rejected", "canceled")
                .set("request_status", STATUS_FAILED).set("error_code", errorCode)
                .set("error_message", "选区协助模型调用失败，可按失败步骤重试")
                .setSql("version = version + 1"));
        updateTaskStatus(entity.getAiTaskId(), STATUS_FAILED, errorCode, "选区协助模型调用失败");
    }

    private void validateBasicRequest(CreateRequest request) {
        if (request == null || request.baseVersion() == null || !StringUtils.hasText(request.contentHash())
                || request.selectionStart() == null || request.selectionEnd() == null
                || request.selectedText() == null || !OPERATIONS.contains(request.operation())
                || !StringUtils.hasText(request.idempotencyKey()) || request.idempotencyKey().length() > 128) {
            throw badRequest("选区、操作、正文版本、正文哈希和 idempotencyKey 必须符合契约");
        }
        if (request.selectedText().length() > MAX_SELECTED_LENGTH) {
            throw badRequest("选区原文超过局部候选长度限制");
        }
        if (request.instruction() != null && request.instruction().length() > MAX_INSTRUCTION_LENGTH) {
            throw badRequest("instruction 不能超过 2000 字符");
        }
    }

    private void validateCurrentChapter(ChapterEntity chapter, CreateRequest request) {
        String content = content(chapter);
        if (!request.baseVersion().equals(chapter.getVersion()) || !request.contentHash().equals(hash(content))) {
            throw new BusinessException(ErrorCode.CHAPTER_VERSION_CONFLICT, "章节正文版本或哈希已变化");
        }
        if (!validRange(content, request.selectionStart(), request.selectionEnd())
                || !request.selectedText().equals(content.substring(request.selectionStart(), request.selectionEnd()))) {
            throw badRequest("选区偏移与原文不匹配");
        }
        if (!OPERATION_DISCUSS.equals(request.operation())
                && !WORKFLOW_CO_CREATION.equals(chapter.getWorkflowStatus())) {
            throw conflict("已发布章节只允许讨论，局部修改需等待修订草稿");
        }
    }

    private boolean matchesRequest(ChapterSelectionAssistanceEntity existing, CreateRequest request) {
        return Objects.equals(existing.getBaseChapterVersion(), request.baseVersion())
                && Objects.equals(existing.getBaseContentHash(), request.contentHash())
                && Objects.equals(existing.getSelectionStart(), request.selectionStart())
                && Objects.equals(existing.getSelectionEnd(), request.selectionEnd())
                && Objects.equals(existing.getSelectedText(), request.selectedText())
                && Objects.equals(existing.getOperationType(), request.operation())
                && Objects.equals(existing.getUserInstruction(), normalizeInstruction(request.instruction()))
                && Objects.equals(existing.getParentId(), request.parentId());
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

    private String inputFingerprint(ChapterEntity chapter, CreateRequest request,
            ChapterSelectionAssistanceEntity parent, GenerationBriefPreview brief) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("chapterId", chapter.getId());
        input.put("baseVersion", request.baseVersion());
        input.put("contentHash", request.contentHash());
        input.put("selectionStart", request.selectionStart());
        input.put("selectionEnd", request.selectionEnd());
        input.put("selectedText", request.selectedText());
        input.put("operation", request.operation());
        input.put("instruction", normalizeInstruction(request.instruction()));
        input.put("parentId", parent == null ? null : parent.getId());
        input.put("parentResult", parent == null ? null : parent.getResultContent());
        input.put("briefFingerprint", brief.fingerprint());
        return hash(json(input));
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
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
        boolean canAccept = !OPERATION_DISCUSS.equals(entity.getOperationType())
                && Set.of(STATUS_READY, STATUS_REVIEW_REQUIRED).contains(entity.getRequestStatus());
        return new View(entity.getId(), entity.getWorkId(), entity.getChapterId(), entity.getParentId(),
                entity.getAiTaskId(), entity.getAgentRunId(), entity.getOperationType(), entity.getRequestStatus(),
                entity.getBaseChapterVersion(), entity.getBaseContentHash(), entity.getSelectionStart(),
                entity.getSelectionEnd(), entity.getSelectedText(), entity.getUserInstruction(),
                entity.getBriefTemplateVersion(), entity.getBriefFingerprint(), entity.getInputFingerprint(),
                entity.getResultContent(), diff, entity.getFactRiskStatus(), reasons == null ? List.of() : reasons,
                canAccept, STATUS_REVIEW_REQUIRED.equals(entity.getFactRiskStatus())
                        ? "如需改变故事事实，请提交为规划变更并重新生成候选" : null,
                entity.getErrorCode(), entity.getErrorMessage(), entity.getAcceptedChapterVersion(),
                entity.getVersion(), entity.getGmtCreate(), entity.getGmtModified());
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
        taskMapper.update(null, new UpdateWrapper<AiTaskEntity>().eq("id", taskId)
                .set("task_status", status).set("error_code", errorCode).set("error_message", errorMessage)
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
}
