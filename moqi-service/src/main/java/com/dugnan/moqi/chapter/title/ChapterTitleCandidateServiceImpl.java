package com.dugnan.moqi.chapter.title;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.entity.ChapterTitleCandidateBatchEntity;
import com.dugnan.moqi.chapter.entity.ChapterTitleCandidateEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterTitleCandidateBatchMapper;
import com.dugnan.moqi.chapter.mapper.ChapterTitleCandidateMapper;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.AdoptRequest;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.AdoptedTitleView;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.BatchView;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.CandidateView;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.CreateBatchRequest;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.LatestBatchView;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.RetryRequest;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.api.PublicFailure;
import com.dugnan.moqi.common.api.PublicFailureFactory;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 持久化章节 AI 标题候选，冻结已保存正文并实施显式采用门禁。
 */
@Service
public class ChapterTitleCandidateServiceImpl implements ChapterTitleCandidateService {

    public static final String WORKFLOW_TYPE = "chapter_title_candidate_v1";
    public static final String GENERATE_STEP = "generate_title_candidates";
    public static final String PROMPT_TEMPLATE_VERSION = "chapter-title-candidate-v1";
    private static final String LOCAL_USER = "local-user";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELED = "canceled";
    private static final String SOURCE_FORMAL = "formal";
    private static final String SOURCE_CANDIDATE = "candidate";
    private static final String CHAPTER_PREFIX_PATTERN = "^\\s*第\\s*[0-9一二三四五六七八九十百千]+\\s*章.*";
    private static final String CANDIDATE_OBJECT_PATTERN = "^candidate:[1-9]\\d*$";
    private static final int CANDIDATE_COUNT = 3;
    private static final int MAX_TITLE_LENGTH = 200;

    private final ChapterMapper chapterMapper;
    private final WorkMapper workMapper;
    private final ChapterProseCandidateMapper proseCandidateMapper;
    private final ChapterTitleCandidateBatchMapper batchMapper;
    private final ChapterTitleCandidateMapper candidateMapper;
    private final AiTaskMapper taskMapper;
    private AgentRuntime agentRuntime;

    public ChapterTitleCandidateServiceImpl(
            ChapterMapper chapterMapper,
            WorkMapper workMapper,
            ChapterProseCandidateMapper proseCandidateMapper,
            ChapterTitleCandidateBatchMapper batchMapper,
            ChapterTitleCandidateMapper candidateMapper,
            AiTaskMapper taskMapper) {
        this.chapterMapper = chapterMapper;
        this.workMapper = workMapper;
        this.proseCandidateMapper = proseCandidateMapper;
        this.batchMapper = batchMapper;
        this.candidateMapper = candidateMapper;
        this.taskMapper = taskMapper;
    }

    @Autowired
    public void setAgentRuntime(@Lazy AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public BatchView create(Long chapterId, CreateBatchRequest request) {
        validateCreate(request);
        ChapterEntity chapter = requireLockedChapter(chapterId);
        String idempotencyKey = request.idempotencyKey().trim();
        ChapterTitleCandidateBatchEntity existing = findByIdempotency(chapterId, idempotencyKey);
        Source source = resolveSource(chapter, request);
        String prompt = prompt(chapter, source);
        String fingerprint = hash(source.kind() + "\n" + source.objectId() + "\n" + source.version()
                + "\n" + source.contentHash() + "\n" + prompt);
        if (existing != null) {
            if (!Objects.equals(existing.getInputFingerprint(), fingerprint)) {
                throw conflict("idempotencyKey 已绑定不同的取名输入");
            }
            return view(existing);
        }

        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(WORKFLOW_TYPE);
        task.setTaskStatus(STATUS_QUEUED);
        task.setWorkId(chapter.getWorkId());
        task.setChapterId(chapter.getId());
        task.setTaskInputJson("{\"inputFingerprint\":\"" + fingerprint + "\"}");
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);

        ChapterTitleCandidateBatchEntity batch = new ChapterTitleCandidateBatchEntity();
        batch.setWorkId(chapter.getWorkId());
        batch.setChapterId(chapter.getId());
        batch.setAiTaskId(task.getId());
        batch.setIdempotencyKey(idempotencyKey);
        batch.setBatchStatus(STATUS_QUEUED);
        batch.setSourceKind(source.kind());
        batch.setSourceObjectId(source.objectId());
        batch.setSourceCandidateId(source.candidateId());
        batch.setSourceVersion(source.version());
        batch.setSourceContentHash(source.contentHash());
        batch.setSourceContentSnapshot(source.content());
        batch.setPromptContent(prompt);
        batch.setInputFingerprint(fingerprint);
        batch.setPromptTemplateVersion(PROMPT_TEMPLATE_VERSION);
        batch.setCurrentAttempt(0);
        batch.setDeleted(0);
        batch.setVersion(0);
        batchMapper.insert(batch);
        AgentRunView run = agentRuntime.start(new StartAgentRunCommand(
                LOCAL_USER,
                chapter.getWorkId(),
                chapter.getId(),
                WORKFLOW_TYPE,
                idempotencyKey,
                chapter.getVersion().longValue(),
                Map.of("batchId", batch.getId(), "workId", chapter.getWorkId(), "chapterId", chapter.getId(),
                        "aiTaskId", task.getId()),
                task.getId()));
        batchMapper.update(null, new UpdateWrapper<ChapterTitleCandidateBatchEntity>()
                .eq("id", batch.getId()).set("agent_run_id", run.runId()).setSql("version = version + 1"));
        return get(chapterId, batch.getId());
    }

    @Override
    public LatestBatchView latest(Long chapterId, String sourceKind, String sourceObjectId) {
        requireChapter(chapterId);
        if (!Set.of(SOURCE_FORMAL, SOURCE_CANDIDATE).contains(sourceKind)
                || !StringUtils.hasText(sourceObjectId)) {
            throw badRequest("正文来源不完整");
        }
        ChapterTitleCandidateBatchEntity batch = batchMapper.selectOne(
                new LambdaQueryWrapper<ChapterTitleCandidateBatchEntity>()
                        .eq(ChapterTitleCandidateBatchEntity::getChapterId, chapterId)
                        .eq(ChapterTitleCandidateBatchEntity::getSourceKind, sourceKind)
                        .eq(ChapterTitleCandidateBatchEntity::getSourceObjectId, sourceObjectId)
                        .eq(ChapterTitleCandidateBatchEntity::getDeleted, 0)
                        .orderByDesc(ChapterTitleCandidateBatchEntity::getId)
                        .last("LIMIT 1"));
        return new LatestBatchView(batch == null ? null : view(batch));
    }

    @Override
    public BatchView get(Long chapterId, Long batchId) {
        ChapterTitleCandidateBatchEntity batch = requireBatch(batchId);
        if (!Objects.equals(chapterId, batch.getChapterId())) {
            throw new BusinessException(ErrorCode.AI_TASK_NOT_FOUND, "取名批次不存在");
        }
        return view(batch);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public BatchView retry(Long chapterId, Long batchId, RetryRequest request) {
        ChapterTitleCandidateBatchEntity batch = requireLockedBatch(chapterId, batchId);
        if (!STATUS_FAILED.equals(batch.getBatchStatus()) || request == null
                || !Objects.equals(batch.getCurrentAttempt(), request.expectedAttempt())) {
            throw conflict("取名批次状态或尝试次数已变化");
        }
        AgentRunView run = agentRuntime.retryStep(new RetryAgentStepCommand(
                batch.getAgentRunId(), GENERATE_STEP, request.expectedAttempt()));
        batchMapper.update(null, new UpdateWrapper<ChapterTitleCandidateBatchEntity>()
                .eq("id", batchId).set("batch_status", STATUS_QUEUED)
                .set("error_code", null).set("error_message", null).setSql("version = version + 1"));
        updateTask(batch.getAiTaskId(), STATUS_QUEUED, null, null);
        if (run.runId() == null) {
            throw conflict("取名任务未能重新投递");
        }
        return get(chapterId, batchId);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public BatchView cancel(Long chapterId, Long batchId) {
        ChapterTitleCandidateBatchEntity batch = requireLockedBatch(chapterId, batchId);
        if (Set.of(STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELED).contains(batch.getBatchStatus())) {
            return view(batch);
        }
        batchMapper.update(null, new UpdateWrapper<ChapterTitleCandidateBatchEntity>()
                .eq("id", batchId).set("batch_status", STATUS_CANCELED).setSql("version = version + 1"));
        updateTask(batch.getAiTaskId(), STATUS_CANCELED, null, null);
        Long runId = batch.getAgentRunId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                agentRuntime.cancel(runId);
            }
        });
        return get(chapterId, batchId);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AdoptedTitleView adopt(
            Long chapterId,
            Long batchId,
            Long candidateId,
            AdoptRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.userConfirmed())
                || !StringUtils.hasText(request.idempotencyKey())) {
            throw badRequest("采用标题需要明确确认和幂等键");
        }
        String title;
        try {
            title = requiredTitle(request.title());
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
        String adoptionKey = request.idempotencyKey().trim();
        ChapterTitleCandidateBatchEntity batch = requireLockedBatch(chapterId, batchId);
        if (!STATUS_COMPLETED.equals(batch.getBatchStatus())) {
            throw conflict("取名批次尚未完成");
        }
        ChapterTitleCandidateEntity existingKey = candidateMapper.selectOne(
                new LambdaQueryWrapper<ChapterTitleCandidateEntity>()
                        .eq(ChapterTitleCandidateEntity::getAdoptionIdempotencyKey, adoptionKey)
                        .eq(ChapterTitleCandidateEntity::getDeleted, 0));
        if (existingKey != null) {
            if (Objects.equals(existingKey.getBatchId(), batchId)
                    && Objects.equals(existingKey.getId(), candidateId)
                    && Objects.equals(existingKey.getAdoptedTitle(), title)) {
                return adoptedView(batchId, existingKey, true);
            }
            throw conflict("采用幂等键已绑定其他结果");
        }
        ChapterTitleCandidateEntity candidate = candidateMapper.selectByIdForUpdate(batchId, candidateId);
        if (candidate == null) {
            throw new BusinessException(ErrorCode.AI_TASK_NOT_FOUND, "标题候选不存在");
        }
        if (candidate.getAdoptedChapterVersion() != null) {
            throw conflict("该标题候选已被采用");
        }
        if (sourceStale(batch) && !Boolean.TRUE.equals(request.allowStaleSource())) {
            throw new BusinessException(ErrorCode.PROSE_CANDIDATE_CONFLICT, "标题候选基于旧版正文");
        }
        if (request.baseVersion() == null
                || chapterMapper.updateTitleIfVersion(chapterId, title, request.baseVersion()) != 1) {
            throw new BusinessException(ErrorCode.CHAPTER_VERSION_CONFLICT, "章节已更新，请刷新后重试");
        }
        LocalDateTime adoptedAt = LocalDateTime.now();
        candidateMapper.update(null, new UpdateWrapper<ChapterTitleCandidateEntity>()
                .eq("id", candidateId)
                .set("adopted_title", title)
                .set("adoption_idempotency_key", adoptionKey)
                .set("adopted_chapter_version", request.baseVersion() + 1)
                .set("adopted_at", adoptedAt)
                .setSql("version = version + 1"));
        candidate.setAdoptedTitle(title);
        candidate.setAdoptionIdempotencyKey(adoptionKey);
        candidate.setAdoptedChapterVersion(request.baseVersion() + 1);
        candidate.setAdoptedAt(adoptedAt);
        return adoptedView(batchId, candidate, false);
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public boolean markRunning(Long batchId, int attempt) {
        ChapterTitleCandidateBatchEntity batch = requireLockedBatch(null, batchId);
        if (STATUS_CANCELED.equals(batch.getBatchStatus())) {
            return false;
        }
        batchMapper.update(null, new UpdateWrapper<ChapterTitleCandidateBatchEntity>()
                .eq("id", batchId).set("batch_status", STATUS_RUNNING).set("current_attempt", attempt)
                .set("error_code", null).set("error_message", null).setSql("version = version + 1"));
        updateTask(batch.getAiTaskId(), STATUS_RUNNING, null, null);
        return true;
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void complete(Long batchId, List<String> titles) {
        ChapterTitleCandidateBatchEntity batch = requireLockedBatch(null, batchId);
        if (STATUS_CANCELED.equals(batch.getBatchStatus())) {
            return;
        }
        List<String> normalized = titles == null ? List.of() : titles.stream().map(this::requiredTitle).toList();
        if (normalized.size() != CANDIDATE_COUNT
                || normalized.stream().distinct().count() != CANDIDATE_COUNT) {
            throw new IllegalArgumentException("模型必须返回 3 个不同标题");
        }
        long existing = candidateMapper.selectCount(new LambdaQueryWrapper<ChapterTitleCandidateEntity>()
                .eq(ChapterTitleCandidateEntity::getBatchId, batchId)
                .eq(ChapterTitleCandidateEntity::getDeleted, 0));
        if (existing == 0) {
            for (int index = 0; index < normalized.size(); index++) {
                ChapterTitleCandidateEntity candidate = new ChapterTitleCandidateEntity();
                candidate.setBatchId(batchId);
                candidate.setCandidateOrder(index + 1);
                candidate.setTitle(normalized.get(index));
                candidate.setDeleted(0);
                candidate.setVersion(0);
                candidateMapper.insert(candidate);
            }
        }
        batchMapper.update(null, new UpdateWrapper<ChapterTitleCandidateBatchEntity>()
                .eq("id", batchId).set("batch_status", STATUS_COMPLETED)
                .set("error_code", null).set("error_message", null).setSql("version = version + 1"));
        updateTask(batch.getAiTaskId(), STATUS_COMPLETED, null, null);
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void fail(Long batchId, String errorCode, String errorMessage) {
        ChapterTitleCandidateBatchEntity batch = requireLockedBatch(null, batchId);
        if (STATUS_CANCELED.equals(batch.getBatchStatus())) {
            return;
        }
        String safeMessage = PublicFailureFactory.safeMessage(errorCode, errorMessage);
        batchMapper.update(null, new UpdateWrapper<ChapterTitleCandidateBatchEntity>()
                .eq("id", batchId).set("batch_status", STATUS_FAILED).set("error_code", errorCode)
                .set("error_message", safeMessage).setSql("version = version + 1"));
        updateTask(batch.getAiTaskId(), STATUS_FAILED, errorCode, errorMessage);
    }

    public String modelPrompt(Long batchId) {
        return requireBatch(batchId).getPromptContent();
    }

    public String sourceFingerprint(Long batchId) {
        return requireBatch(batchId).getInputFingerprint();
    }

    private Source resolveSource(ChapterEntity chapter, CreateBatchRequest request) {
        if (SOURCE_FORMAL.equals(request.sourceKind())) {
            String objectId = SOURCE_FORMAL + ":" + chapter.getId();
            String content = text(chapter.getContent());
            verifySource(request, objectId, chapter.getVersion(), hash(content));
            requireContent(content);
            return new Source(SOURCE_FORMAL, objectId, null, chapter.getVersion(), hash(content), content);
        }
        if (SOURCE_CANDIDATE.equals(request.sourceKind())) {
            Long candidateId = parseCandidateId(request.sourceObjectId());
            ChapterProseCandidateEntity candidate = proseCandidateMapper.selectByIdForUpdate(chapter.getId(), candidateId);
            if (candidate == null || !Objects.equals(candidate.getWorkId(), chapter.getWorkId())) {
                throw new BusinessException(ErrorCode.PROSE_CANDIDATE_NOT_FOUND, "正文候选不存在");
            }
            String objectId = SOURCE_CANDIDATE + ":" + candidate.getId();
            verifySource(request, objectId, candidate.getVersion(), candidate.getContentHash());
            requireContent(candidate.getContent());
            return new Source(SOURCE_CANDIDATE, objectId, candidate.getId(), candidate.getVersion(),
                    candidate.getContentHash(), candidate.getContent());
        }
        throw badRequest("正文来源类型无效");
    }

    private void verifySource(CreateBatchRequest request, String objectId, Integer version, String contentHash) {
        if (!Objects.equals(objectId, request.sourceObjectId())
                || !Objects.equals(version, request.sourceVersion())
                || !Objects.equals(contentHash, request.contentHash())) {
            throw new BusinessException(ErrorCode.PROSE_CANDIDATE_CONFLICT, "取名正文版本或哈希已变化");
        }
    }

    private String prompt(ChapterEntity chapter, Source source) {
        WorkEntity work = workMapper.selectById(chapter.getWorkId());
        if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        List<ChapterEntity> siblings = chapterMapper.selectList(new LambdaQueryWrapper<ChapterEntity>()
                .eq(ChapterEntity::getWorkId, chapter.getWorkId())
                .eq(ChapterEntity::getDeleted, 0)
                .orderByAsc(ChapterEntity::getChapterNo));
        String previous = neighborTitle(siblings, chapter.getChapterNo(), -1);
        String next = neighborTitle(siblings, chapter.getChapterNo(), 1);
        StringBuilder prompt = new StringBuilder();
        prompt.append("本轮任务：为下列已保存章节正文提供 3 个风格有差异的短标题候选。\n")
                .append("作品：").append(work.getTitle()).append('\n')
                .append("章序：第 ").append(chapter.getChapterNo()).append(" 章\n")
                .append("当前正式标题：").append(StringUtils.hasText(chapter.getTitle()) ? chapter.getTitle() : "尚未命名").append('\n')
                .append("上一章已确认标题：").append(previous).append('\n')
                .append("下一章已确认标题：").append(next).append('\n')
                .append("正文来源：").append(source.kind()).append('\n')
                .append("需要取名的已保存正文：\n").append(source.content());
        return prompt.toString();
    }

    private String neighborTitle(List<ChapterEntity> chapters, int chapterNo, int direction) {
        return chapters.stream()
                .filter(item -> direction < 0 ? item.getChapterNo() < chapterNo : item.getChapterNo() > chapterNo)
                .filter(item -> StringUtils.hasText(item.getTitle()))
                .min(direction < 0
                        ? Comparator.comparing(ChapterEntity::getChapterNo).reversed()
                        : Comparator.comparing(ChapterEntity::getChapterNo))
                .map(ChapterEntity::getTitle)
                .orElse("无");
    }

    private boolean sourceStale(ChapterTitleCandidateBatchEntity batch) {
        if (SOURCE_FORMAL.equals(batch.getSourceKind())) {
            ChapterEntity chapter = chapterMapper.selectById(batch.getChapterId());
            return chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())
                    || !Objects.equals(hash(text(chapter.getContent())), batch.getSourceContentHash());
        }
        ChapterProseCandidateEntity candidate = proseCandidateMapper.selectById(batch.getSourceCandidateId());
        return candidate == null || Integer.valueOf(1).equals(candidate.getDeleted())
                || !Objects.equals(candidate.getContentHash(), batch.getSourceContentHash());
    }

    private BatchView view(ChapterTitleCandidateBatchEntity batch) {
        List<CandidateView> candidates = candidateMapper.selectList(
                new LambdaQueryWrapper<ChapterTitleCandidateEntity>()
                        .eq(ChapterTitleCandidateEntity::getBatchId, batch.getId())
                        .eq(ChapterTitleCandidateEntity::getDeleted, 0)
                        .orderByAsc(ChapterTitleCandidateEntity::getCandidateOrder))
                .stream().map(item -> new CandidateView(item.getId(), item.getCandidateOrder(), item.getTitle(),
                        item.getAdoptedTitle(), item.getAdoptedChapterVersion(), item.getAdoptedAt())).toList();
        return new BatchView(batch.getId(), batch.getWorkId(), batch.getChapterId(), batch.getAiTaskId(),
                batch.getAgentRunId(), batch.getBatchStatus(), batch.getSourceKind(), batch.getSourceObjectId(),
                batch.getSourceVersion(), batch.getSourceContentHash(), sourceStale(batch), batch.getCurrentAttempt(),
                candidates, batch.getErrorCode(), safeError(batch.getErrorCode(), batch.getErrorMessage()),
                publicFailure(batch), batch.getVersion(), batch.getGmtCreate(), batch.getGmtModified());
    }

    private PublicFailure publicFailure(ChapterTitleCandidateBatchEntity batch) {
        if (!StringUtils.hasText(batch.getErrorCode())) {
            return null;
        }
        AiTaskEntity task = taskMapper.selectById(batch.getAiTaskId());
        return PublicFailureFactory.from(batch.getErrorCode(), task == null ? null : task.getDiagnosticRef());
    }

    private String safeError(String errorCode, String message) {
        return StringUtils.hasText(errorCode) ? PublicFailureFactory.safeMessage(errorCode, message) : null;
    }

    private AdoptedTitleView adoptedView(
            Long batchId,
            ChapterTitleCandidateEntity candidate,
            boolean replay) {
        return new AdoptedTitleView(batchId, candidate.getId(), candidate.getAdoptedTitle(),
                candidate.getAdoptedChapterVersion(), replay, candidate.getAdoptedAt());
    }

    private ChapterTitleCandidateBatchEntity requireBatch(Long batchId) {
        ChapterTitleCandidateBatchEntity batch = batchId == null ? null : batchMapper.selectById(batchId);
        if (batch == null || Integer.valueOf(1).equals(batch.getDeleted())) {
            throw new BusinessException(ErrorCode.AI_TASK_NOT_FOUND, "取名批次不存在");
        }
        return batch;
    }

    private ChapterTitleCandidateBatchEntity requireLockedBatch(Long chapterId, Long batchId) {
        ChapterTitleCandidateBatchEntity batch = batchMapper.selectByIdForUpdate(batchId);
        if (batch == null) {
            throw new BusinessException(ErrorCode.AI_TASK_NOT_FOUND, "取名批次不存在");
        }
        if (chapterId != null && !Objects.equals(chapterId, batch.getChapterId())) {
            throw new BusinessException(ErrorCode.AI_TASK_NOT_FOUND, "取名批次不存在");
        }
        return batch;
    }

    private ChapterTitleCandidateBatchEntity findByIdempotency(Long chapterId, String key) {
        return batchMapper.selectOne(new LambdaQueryWrapper<ChapterTitleCandidateBatchEntity>()
                .eq(ChapterTitleCandidateBatchEntity::getChapterId, chapterId)
                .eq(ChapterTitleCandidateBatchEntity::getIdempotencyKey, key)
                .eq(ChapterTitleCandidateBatchEntity::getDeleted, 0));
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

    private void validateCreate(CreateBatchRequest request) {
        if (request == null || !StringUtils.hasText(request.sourceKind())
                || !StringUtils.hasText(request.sourceObjectId()) || request.sourceVersion() == null
                || !StringUtils.hasText(request.contentHash()) || !request.contentHash().matches("[a-f0-9]{64}")
                || !StringUtils.hasText(request.idempotencyKey()) || request.idempotencyKey().trim().length() > 128) {
            throw badRequest("取名请求不完整");
        }
    }

    private void requireContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw badRequest("正文形成后才能使用 AI 取名");
        }
    }

    private String requiredTitle(String raw) {
        String title = raw == null ? "" : raw.trim();
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("标题候选不能为空");
        }
        if (title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("标题候选超过 200 个字符");
        }
        boolean hasChapterPrefix = title.matches(CHAPTER_PREFIX_PATTERN);
        boolean hasTitleLabel = title.matches("^标题\\s*[:：].*");
        boolean hasBookTitleWrapper = title.startsWith("《") && title.endsWith("》");
        if (hasChapterPrefix || hasTitleLabel || hasBookTitleWrapper) {
            throw new IllegalArgumentException("标题候选不得包含章序、标题前缀或书名号包装");
        }
        return title;
    }

    private Long parseCandidateId(String objectId) {
        if (objectId == null || !objectId.matches(CANDIDATE_OBJECT_PATTERN)) {
            throw badRequest("正文候选对象标识无效");
        }
        return Long.valueOf(objectId.substring("candidate:".length()));
    }

    private void updateTask(Long taskId, String status, String errorCode, String errorMessage) {
        boolean failed = STATUS_FAILED.equals(status);
        AiTaskEntity task = taskMapper.selectById(taskId);
        String diagnosticRef = task != null && StringUtils.hasText(task.getDiagnosticRef())
                ? task.getDiagnosticRef() : failed ? PublicFailureFactory.newDiagnosticRef() : null;
        taskMapper.update(null, new UpdateWrapper<AiTaskEntity>().eq("id", taskId)
                .set("task_status", status).set("error_code", errorCode)
                .set("error_message", failed ? PublicFailureFactory.safeMessage(errorCode, errorMessage) : null)
                .set(failed, "diagnostic_ref", diagnosticRef).setSql("version = version + 1"));
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.AI_TASK_STATE_CONFLICT, message);
    }

    private record Source(
            String kind,
            String objectId,
            Long candidateId,
            Integer version,
            String contentHash,
            String content) {
    }
}
