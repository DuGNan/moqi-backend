package com.dugnan.moqi.knowledge.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.RetryAgentStepCommand;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.BatchView;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.CandidateDecision;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.CandidateView;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ConfirmCandidateRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.Evidence;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ExtractedCandidate;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ExtractionOutput;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.IgnoreCandidateRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.RetryExtractionRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.StartExtractionRequest;
import com.dugnan.moqi.knowledge.entity.ChapterKeyEventEntity;
import com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity;
import com.dugnan.moqi.knowledge.entity.ForeshadowingItemEntity;
import com.dugnan.moqi.knowledge.entity.SettingEntryEntity;
import com.dugnan.moqi.knowledge.entity.StoryKnowledgeCandidateEntity;
import com.dugnan.moqi.knowledge.entity.StoryKnowledgeExtractionBatchEntity;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeCandidateMapper;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeExtractionBatchMapper;
import com.dugnan.moqi.knowledge.service.KnowledgeExtractionService;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 实现已采纳正文的冻结提取、候选校验、去重冲突和人工确认写入。
 */
@Service
public class KnowledgeExtractionServiceImpl implements KnowledgeExtractionService {

    public static final String WORKFLOW_TYPE = "story_knowledge_extraction_v1";
    public static final String EXTRACTOR_VERSION = "story-knowledge-extractor-v1";
    private static final String LOCAL_USER = "local-user";
    private static final String STATUS_ACCEPTED = "accepted";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_CONFLICT = "conflict";
    private static final String STATUS_DUPLICATE = "duplicate";
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_IGNORED = "ignored";
    private static final String STATUS_STALE = "stale";
    private static final String STATUS_CANCELED = "canceled";
    private static final String STATUS_TIMED_OUT = "timed_out";
    private static final String TYPE_CHAPTER_SUMMARY = "chapter_summary";
    private static final String TYPE_KEY_EVENT = "key_event";
    private static final String TYPE_SETTING = "setting";
    private static final String TYPE_FORESHADOWING = "foreshadowing";
    private static final String ACTION_SEED = "seed";
    private static final String ACTION_ADVANCE = "advance";
    private static final String FIELD_ACTION = "action";
    private static final String RESOLUTION_CREATE = "create";
    private static final String RESOLUTION_MERGE = "merge";
    private static final String RESOLUTION_REPLACE = "replace";
    private static final Set<String> CANDIDATE_TYPES =
            Set.of(TYPE_CHAPTER_SUMMARY, TYPE_KEY_EVENT, TYPE_SETTING, TYPE_FORESHADOWING);
    private static final Set<String> SETTING_TYPES =
            Set.of("character", "place", "organization", "rule", "item", "other");
    private static final Set<String> EVENT_TYPES =
            Set.of("plot", "character", "world_rule", "relationship", "foreshadowing");
    private static final Set<String> FORESHADOWING_ACTIONS =
            Set.of(ACTION_SEED, ACTION_ADVANCE, "payoff");
    private static final int MAX_CANDIDATES = 50;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_TEXT_LENGTH = 20000;

    private final StoryKnowledgeExtractionBatchMapper batchMapper;
    private final StoryKnowledgeCandidateMapper candidateMapper;
    private final ChapterGenerationMapper generationMapper;
    private final ChapterMapper chapterMapper;
    private final AiTaskMapper taskMapper;
    private final SettingEntryMapper settingMapper;
    private final ForeshadowingItemMapper foreshadowingMapper;
    private final ChapterSummaryMapper summaryMapper;
    private final ChapterKeyEventMapper eventMapper;
    private final AgentRuntime agentRuntime;
    private final ObjectMapper objectMapper;
    private final KnowledgeExtractionStaleMarker staleMarker;

    public KnowledgeExtractionServiceImpl(
            StoryKnowledgeExtractionBatchMapper batchMapper,
            StoryKnowledgeCandidateMapper candidateMapper,
            ChapterGenerationMapper generationMapper,
            ChapterMapper chapterMapper,
            AiTaskMapper taskMapper,
            SettingEntryMapper settingMapper,
            ForeshadowingItemMapper foreshadowingMapper,
            ChapterSummaryMapper summaryMapper,
            ChapterKeyEventMapper eventMapper,
            AgentRuntime agentRuntime,
            ObjectMapper objectMapper,
            KnowledgeExtractionStaleMarker staleMarker) {
        this.batchMapper = batchMapper;
        this.candidateMapper = candidateMapper;
        this.generationMapper = generationMapper;
        this.chapterMapper = chapterMapper;
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.foreshadowingMapper = foreshadowingMapper;
        this.summaryMapper = summaryMapper;
        this.eventMapper = eventMapper;
        this.agentRuntime = agentRuntime;
        this.objectMapper = objectMapper;
        this.staleMarker = staleMarker;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public BatchView startAcceptedGeneration(Long generationId) {
        ChapterGenerationEntity generation = requireAcceptedGeneration(null, generationId);
        return createOrReuse(generation.getChapterId(), generation, automaticKey(generationId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public BatchView start(Long chapterId, Long generationId, StartExtractionRequest request) {
        if (request == null || !StringUtils.hasText(request.idempotencyKey())) {
            throw invalid("idempotencyKey 不能为空");
        }
        return createOrReuse(chapterId, requireAcceptedGeneration(chapterId, generationId),
                request.idempotencyKey().trim());
    }

    @Override
    public BatchView latest(Long chapterId, Long generationId) {
        requireAcceptedGeneration(chapterId, generationId);
        StoryKnowledgeExtractionBatchEntity batch = batchMapper.selectOne(
                new LambdaQueryWrapper<StoryKnowledgeExtractionBatchEntity>()
                        .eq(StoryKnowledgeExtractionBatchEntity::getChapterId, chapterId)
                        .eq(StoryKnowledgeExtractionBatchEntity::getGenerationId, generationId)
                        .eq(StoryKnowledgeExtractionBatchEntity::getDeleted, 0)
                        .orderByDesc(StoryKnowledgeExtractionBatchEntity::getId)
                        .last("LIMIT 1"));
        return batch == null ? null : view(batch);
    }

    @Override
    public BatchView get(Long chapterId, Long generationId, Long batchId) {
        return view(requireBatch(chapterId, generationId, batchId));
    }

    @Override
    public AgentRunView retry(
            Long chapterId,
            Long generationId,
            Long batchId,
            RetryExtractionRequest request) {
        StoryKnowledgeExtractionBatchEntity batch = requireBatch(chapterId, generationId, batchId);
        if (batch.getAgentRunId() == null || request == null || request.expectedAttempt() == null) {
            throw conflict("当前知识提取批次不能重试");
        }
        return agentRuntime.retryStep(new RetryAgentStepCommand(
                batch.getAgentRunId(), "extract", request.expectedAttempt()));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public AgentRunView cancel(Long chapterId, Long generationId, Long batchId) {
        StoryKnowledgeExtractionBatchEntity batch = requireBatch(chapterId, generationId, batchId);
        if (batch.getAgentRunId() == null) {
            throw conflict("知识提取批次未关联运行任务");
        }
        AgentRunView run = agentRuntime.cancel(batch.getAgentRunId());
        updateTerminal(batchId, Set.of(STATUS_QUEUED, STATUS_RUNNING), STATUS_CANCELED, null);
        return run;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public CandidateDecision confirm(Long candidateId, ConfirmCandidateRequest request) {
        StoryKnowledgeCandidateEntity candidate = requireCandidate(candidateId);
        if (STATUS_CONFIRMED.equals(candidate.getCandidateStatus())) {
            return decision(candidate);
        }
        ensureCurrent(requireBatchById(candidate.getBatchId()));
        if (request == null || request.baseVersion() == null
                || !request.baseVersion().equals(candidate.getVersion())) {
            throw conflict("知识候选版本已变化，请刷新后重试");
        }
        if (!Set.of(STATUS_PENDING, STATUS_CONFLICT).contains(candidate.getCandidateStatus())) {
            throw conflict("当前知识候选状态不允许确认");
        }
        String resolution = normalizeResolution(candidate, request.resolution());
        Map<String, Object> payload = request.resolvedPayload() == null
                ? payload(candidate)
                : Map.copyOf(request.resolvedPayload());
        validatePayload(candidate.getCandidateType(), payload, candidate.getWorkId());
        Target target = writeTarget(candidate, payload, resolution, request.mergeTargetId());
        int changed = candidateMapper.update(null, new UpdateWrapper<StoryKnowledgeCandidateEntity>()
                .eq("id", candidateId)
                .eq("version", candidate.getVersion())
                .in("candidate_status", STATUS_PENDING, STATUS_CONFLICT)
                .set("candidate_status", STATUS_CONFIRMED)
                .set("payload_json", json(payload))
                .set("confirmed_target_type", target.type())
                .set("confirmed_target_id", target.id())
                .setSql("version = version + 1"));
        if (changed != 1) {
            throw conflict("知识候选已被其他操作更新");
        }
        return decision(requireCandidate(candidateId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public CandidateDecision ignore(Long candidateId, IgnoreCandidateRequest request) {
        StoryKnowledgeCandidateEntity candidate = requireCandidate(candidateId);
        if (STATUS_IGNORED.equals(candidate.getCandidateStatus())) {
            return decision(candidate);
        }
        if (request == null || request.baseVersion() == null
                || !request.baseVersion().equals(candidate.getVersion())) {
            throw conflict("知识候选版本已变化，请刷新后重试");
        }
        int changed = candidateMapper.update(null, new UpdateWrapper<StoryKnowledgeCandidateEntity>()
                .eq("id", candidateId)
                .eq("version", candidate.getVersion())
                .in("candidate_status", STATUS_PENDING, STATUS_CONFLICT, STATUS_DUPLICATE)
                .set("candidate_status", STATUS_IGNORED)
                .setSql("version = version + 1"));
        if (changed != 1) {
            throw conflict("知识候选已被其他操作更新");
        }
        return decision(requireCandidate(candidateId));
    }

    public void markRunning(Long batchId) {
        batchMapper.update(null, new UpdateWrapper<StoryKnowledgeExtractionBatchEntity>()
                .eq("id", batchId).eq("batch_status", STATUS_QUEUED)
                .set("batch_status", STATUS_RUNNING).setSql("version = version + 1"));
    }

    public String sourceContent(Long batchId) {
        StoryKnowledgeExtractionBatchEntity batch = requireBatchById(batchId);
        ensureCurrent(batch);
        return batch.getSourceContent();
    }

    public String sourceFingerprint(Long batchId) {
        return requireBatchById(batchId).getSourceFingerprint();
    }

    public ExtractionOutput validateOutput(Long batchId, ExtractionOutput output) {
        StoryKnowledgeExtractionBatchEntity batch = requireBatchById(batchId);
        ensureCurrent(batch);
        if (output == null || !Integer.valueOf(1).equals(output.schemaVersion())
                || output.candidates() == null || output.candidates().isEmpty()
                || output.candidates().size() > MAX_CANDIDATES) {
            throw invalid("知识提取输出的 schemaVersion 或候选数量非法");
        }
        Set<String> keys = new HashSet<>();
        boolean hasSummary = false;
        for (ExtractedCandidate item : output.candidates()) {
            validateCandidate(batch, item, keys);
            hasSummary = hasSummary || TYPE_CHAPTER_SUMMARY.equals(item.candidateType());
        }
        if (!hasSummary) {
            throw invalid("知识提取必须包含章节摘要候选");
        }
        return new ExtractionOutput(1, List.copyOf(output.candidates()));
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void persist(Long batchId, ExtractionOutput output) {
        StoryKnowledgeExtractionBatchEntity batch = requireBatchById(batchId);
        ensureCurrent(batch);
        if (candidateCount(batchId) > 0) {
            finishPersistence(batch, candidateCount(batchId));
            return;
        }
        for (ExtractedCandidate item : output.candidates()) {
            StoryKnowledgeCandidateEntity entity = candidateEntity(batch, item);
            classify(entity, item.payload());
            candidateMapper.insert(entity);
        }
        finishPersistence(batch, candidateCount(batchId));
    }

    private void finishPersistence(StoryKnowledgeExtractionBatchEntity batch, int count) {
        batchMapper.update(null, new UpdateWrapper<StoryKnowledgeExtractionBatchEntity>()
                .eq("id", batch.getId())
                .in("batch_status", STATUS_RUNNING, STATUS_QUEUED)
                .set("batch_status", STATUS_READY)
                .set("candidate_count", count)
                .set("error_code", null)
                .setSql("version = version + 1"));
        taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", batch.getAiTaskId())
                .set("task_status", "succeeded")
                .setSql("version = version + 1"));
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void fail(Long batchId, String errorCode) {
        StoryKnowledgeExtractionBatchEntity batch = requireBatchById(batchId);
        if (Set.of(STATUS_READY, STATUS_STALE, STATUS_CANCELED, STATUS_TIMED_OUT)
                .contains(batch.getBatchStatus())) {
            return;
        }
        updateTerminal(batchId, Set.of(STATUS_QUEUED, STATUS_RUNNING), "failed", errorCode);
        taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", batch.getAiTaskId())
                .set("task_status", "failed")
                .set("error_code", errorCode)
                .setSql("version = version + 1"));
    }

    public void markRunTerminal(Long runId, String runStatus) {
        String target = STATUS_TIMED_OUT.equals(runStatus) ? STATUS_TIMED_OUT
                : STATUS_CANCELED.equals(runStatus) ? STATUS_CANCELED : "failed";
        batchMapper.update(null, new UpdateWrapper<StoryKnowledgeExtractionBatchEntity>()
                .eq("agent_run_id", runId)
                .in("batch_status", STATUS_QUEUED, STATUS_RUNNING)
                .set("batch_status", target)
                .set("error_code", "failed".equals(target) ? "KNOWLEDGE_EXTRACTION_FAILED" : null)
                .setSql("version = version + 1"));
    }

    private BatchView createOrReuse(
            Long chapterId,
            ChapterGenerationEntity generation,
            String idempotencyKey) {
        ChapterEntity chapter = requireChapterForUpdate(chapterId);
        if (!generation.getWorkId().equals(chapter.getWorkId())) {
            throw invalid("已采纳正文与章节不属于同一作品");
        }
        String source = chapter.getContent() == null ? "" : chapter.getContent();
        String fingerprint = fingerprint(generation.getId(), chapter.getVersion(), source);
        StoryKnowledgeExtractionBatchEntity existing = batchMapper.selectOne(
                new LambdaQueryWrapper<StoryKnowledgeExtractionBatchEntity>()
                        .eq(StoryKnowledgeExtractionBatchEntity::getGenerationId, generation.getId())
                        .eq(StoryKnowledgeExtractionBatchEntity::getExtractorVersion, EXTRACTOR_VERSION)
                        .eq(StoryKnowledgeExtractionBatchEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        if (existing != null) {
            if (!fingerprint.equals(existing.getSourceFingerprint())) {
                throw new BusinessException(ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT,
                        "同一已采纳正文和提取器已绑定不同来源版本");
            }
            return view(existing);
        }
        StoryKnowledgeExtractionBatchEntity byKey = batchMapper.selectOne(
                new LambdaQueryWrapper<StoryKnowledgeExtractionBatchEntity>()
                        .eq(StoryKnowledgeExtractionBatchEntity::getIdempotencyKey, idempotencyKey)
                        .eq(StoryKnowledgeExtractionBatchEntity::getDeleted, 0));
        if (byKey != null) {
            if (!fingerprint.equals(byKey.getSourceFingerprint())) {
                throw new BusinessException(ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT,
                        "幂等键已绑定其他知识提取来源");
            }
            return view(byKey);
        }
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(WORKFLOW_TYPE);
        task.setTaskStatus(STATUS_QUEUED);
        task.setWorkId(generation.getWorkId());
        task.setChapterId(chapterId);
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);

        StoryKnowledgeExtractionBatchEntity batch = new StoryKnowledgeExtractionBatchEntity();
        batch.setWorkId(generation.getWorkId());
        batch.setChapterId(chapterId);
        batch.setGenerationId(generation.getId());
        batch.setAiTaskId(task.getId());
        batch.setExtractorVersion(EXTRACTOR_VERSION);
        batch.setIdempotencyKey(idempotencyKey);
        batch.setSourceContentRevision(chapter.getVersion());
        batch.setSourceFingerprint(fingerprint);
        batch.setSourceContent(source);
        batch.setBatchStatus(STATUS_QUEUED);
        batch.setCandidateCount(0);
        batch.setDeleted(0);
        batch.setVersion(0);
        batchMapper.insert(batch);
        task.setTaskInputJson(json(Map.of(
                "batchId", batch.getId(),
                "generationId", generation.getId(),
                "sourceFingerprint", fingerprint)));
        taskMapper.updateById(task);

        AgentRunView run = agentRuntime.start(new StartAgentRunCommand(
                LOCAL_USER, generation.getWorkId(), chapterId, WORKFLOW_TYPE,
                idempotencyKey, chapter.getVersion().longValue(),
                Map.of(
                        "batchId", batch.getId(),
                        "workId", generation.getWorkId(),
                        "chapterId", chapterId,
                        "generationId", generation.getId(),
                        "aiTaskId", task.getId(),
                        "sourceFingerprint", fingerprint),
                task.getId()));
        batchMapper.update(null, new UpdateWrapper<StoryKnowledgeExtractionBatchEntity>()
                .eq("id", batch.getId()).eq("version", batch.getVersion())
                .set("agent_run_id", run.runId()).setSql("version = version + 1"));
        return get(chapterId, generation.getId(), batch.getId());
    }

    private void validateCandidate(
            StoryKnowledgeExtractionBatchEntity batch,
            ExtractedCandidate item,
            Set<String> keys) {
        if (item == null || !StringUtils.hasText(item.candidateKey())
                || item.candidateKey().length() > MAX_KEY_LENGTH
                || !keys.add(item.candidateKey())
                || !CANDIDATE_TYPES.contains(item.candidateType())
                || item.payload() == null || item.evidence() == null) {
            throw invalid("知识候选标识、类型或内容非法");
        }
        Evidence evidence = item.evidence();
        if (evidence.startOffset() == null || evidence.endOffset() == null
                || evidence.startOffset() < 0 || evidence.endOffset() <= evidence.startOffset()
                || evidence.endOffset() > batch.getSourceContent().length()
                || !batch.getSourceContent().substring(
                        evidence.startOffset(), evidence.endOffset()).equals(evidence.text())) {
            throw invalid("知识候选证据范围不属于已采纳正文");
        }
        validatePayload(item.candidateType(), item.payload(), batch.getWorkId());
    }

    private void validatePayload(String type, Map<String, Object> payload, Long workId) {
        if (TYPE_CHAPTER_SUMMARY.equals(type)) {
            requiredText(payload, "summary", MAX_TEXT_LENGTH);
            optionalStringList(payload, "characterChanges", MAX_CANDIDATES);
            optionalStringList(payload, "openQuestions", MAX_CANDIDATES);
            return;
        }
        if (TYPE_KEY_EVENT.equals(type)) {
            requiredText(payload, "title", MAX_TITLE_LENGTH);
            requiredText(payload, "content", MAX_TEXT_LENGTH);
            requiredAllowed(payload, "eventType", EVENT_TYPES);
            requiredPositiveInteger(payload, "occurredOrder");
            validateSettingIds(payload, "relatedSettingIds", workId);
            validateForeshadowingIds(payload, "relatedForeshadowingIds", workId);
            return;
        }
        if (TYPE_SETTING.equals(type)) {
            requiredAllowed(payload, "settingType", SETTING_TYPES);
            requiredText(payload, "name", MAX_TITLE_LENGTH);
            requiredText(payload, "content", MAX_TEXT_LENGTH);
            return;
        }
        String action = requiredAllowed(payload, FIELD_ACTION, FORESHADOWING_ACTIONS);
        requiredText(payload, "title", MAX_TITLE_LENGTH);
        requiredText(payload, "description", MAX_TEXT_LENGTH);
        Long targetId = optionalLong(payload.get("existingForeshadowingId"));
        if (ACTION_SEED.equals(action) && targetId != null) {
            throw invalid("seed 伏笔候选不能引用既有伏笔");
        }
        if (!ACTION_SEED.equals(action)) {
            requireForeshadowing(targetId, workId);
        }
    }

    private StoryKnowledgeCandidateEntity candidateEntity(
            StoryKnowledgeExtractionBatchEntity batch,
            ExtractedCandidate item) {
        StoryKnowledgeCandidateEntity entity = new StoryKnowledgeCandidateEntity();
        entity.setBatchId(batch.getId());
        entity.setWorkId(batch.getWorkId());
        entity.setChapterId(batch.getChapterId());
        entity.setGenerationId(batch.getGenerationId());
        entity.setCandidateKey(item.candidateKey().trim());
        entity.setCandidateType(item.candidateType());
        entity.setCandidateStatus(STATUS_PENDING);
        entity.setPayloadJson(json(item.payload()));
        entity.setEvidenceStartOffset(item.evidence().startOffset());
        entity.setEvidenceEndOffset(item.evidence().endOffset());
        entity.setEvidenceText(item.evidence().text());
        entity.setCandidateFingerprint(hash(item.candidateType() + ":" + canonical(item.payload())));
        entity.setDeleted(0);
        entity.setVersion(0);
        return entity;
    }

    private void classify(StoryKnowledgeCandidateEntity entity, Map<String, Object> payload) {
        StoryKnowledgeCandidateEntity duplicate = candidateMapper.selectOne(
                new LambdaQueryWrapper<StoryKnowledgeCandidateEntity>()
                        .eq(StoryKnowledgeCandidateEntity::getWorkId, entity.getWorkId())
                        .eq(StoryKnowledgeCandidateEntity::getCandidateType, entity.getCandidateType())
                        .eq(StoryKnowledgeCandidateEntity::getCandidateFingerprint, entity.getCandidateFingerprint())
                        .in(StoryKnowledgeCandidateEntity::getCandidateStatus,
                                STATUS_PENDING, STATUS_CONFLICT, STATUS_CONFIRMED, STATUS_DUPLICATE)
                        .eq(StoryKnowledgeCandidateEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        if (duplicate != null) {
            entity.setCandidateStatus(STATUS_DUPLICATE);
            entity.setConflictTargetId(duplicate.getId());
            return;
        }
        Long authorityDuplicate = authorityDuplicateTarget(entity, payload);
        if (authorityDuplicate != null) {
            entity.setCandidateStatus(STATUS_DUPLICATE);
            entity.setConflictTargetId(authorityDuplicate);
            return;
        }
        Long target = authorityConflictTarget(entity, payload);
        if (target != null) {
            entity.setCandidateStatus(STATUS_CONFLICT);
            entity.setConflictTargetId(target);
        }
    }

    private Long authorityDuplicateTarget(
            StoryKnowledgeCandidateEntity entity,
            Map<String, Object> payload) {
        if (TYPE_SETTING.equals(entity.getCandidateType())) {
            SettingEntryEntity item = settingMapper.selectOne(new LambdaQueryWrapper<SettingEntryEntity>()
                    .eq(SettingEntryEntity::getWorkId, entity.getWorkId())
                    .eq(SettingEntryEntity::getSettingType, text(payload, "settingType"))
                    .eq(SettingEntryEntity::getName, text(payload, "name"))
                    .eq(SettingEntryEntity::getContent, text(payload, "content"))
                    .eq(SettingEntryEntity::getEntryStatus, "active")
                    .eq(SettingEntryEntity::getDeleted, 0).last("LIMIT 1"));
            return item == null ? null : item.getId();
        }
        if (TYPE_CHAPTER_SUMMARY.equals(entity.getCandidateType())) {
            ChapterSummaryEntity item = summaryMapper.selectOne(new LambdaQueryWrapper<ChapterSummaryEntity>()
                    .eq(ChapterSummaryEntity::getChapterId, entity.getChapterId())
                    .eq(ChapterSummaryEntity::getSummary, text(payload, "summary"))
                    .eq(ChapterSummaryEntity::getCharacterChangesJson,
                            json(list(payload, "characterChanges")))
                    .eq(ChapterSummaryEntity::getOpenQuestionsJson,
                            json(list(payload, "openQuestions")))
                    .eq(ChapterSummaryEntity::getSummaryStatus, STATUS_CONFIRMED)
                    .eq(ChapterSummaryEntity::getDeleted, 0).last("LIMIT 1"));
            return item == null ? null : item.getId();
        }
        if (TYPE_KEY_EVENT.equals(entity.getCandidateType())) {
            ChapterKeyEventEntity item = eventMapper.selectOne(new LambdaQueryWrapper<ChapterKeyEventEntity>()
                    .eq(ChapterKeyEventEntity::getChapterId, entity.getChapterId())
                    .eq(ChapterKeyEventEntity::getEventTitle, text(payload, "title"))
                    .eq(ChapterKeyEventEntity::getEventContent, text(payload, "content"))
                    .eq(ChapterKeyEventEntity::getEventType, text(payload, "eventType"))
                    .eq(ChapterKeyEventEntity::getOccurredOrder, integer(payload.get("occurredOrder")))
                    .eq(ChapterKeyEventEntity::getRelatedSettingIdsJson,
                            json(longList(payload, "relatedSettingIds")))
                    .eq(ChapterKeyEventEntity::getRelatedForeshadowingIdsJson,
                            json(longList(payload, "relatedForeshadowingIds")))
                    .eq(ChapterKeyEventEntity::getDeleted, 0).last("LIMIT 1"));
            return item == null ? null : item.getId();
        }
        if (ACTION_SEED.equals(text(payload, FIELD_ACTION))) {
            ForeshadowingItemEntity item = foreshadowingMapper.selectOne(
                    new LambdaQueryWrapper<ForeshadowingItemEntity>()
                            .eq(ForeshadowingItemEntity::getWorkId, entity.getWorkId())
                            .eq(ForeshadowingItemEntity::getTitle, text(payload, "title"))
                            .eq(ForeshadowingItemEntity::getDescription, text(payload, "description"))
                            .eq(ForeshadowingItemEntity::getDeleted, 0).last("LIMIT 1"));
            return item == null ? null : item.getId();
        }
        return null;
    }

    private Long authorityConflictTarget(
            StoryKnowledgeCandidateEntity entity,
            Map<String, Object> payload) {
        if (TYPE_SETTING.equals(entity.getCandidateType())) {
            SettingEntryEntity item = settingMapper.selectOne(new LambdaQueryWrapper<SettingEntryEntity>()
                    .eq(SettingEntryEntity::getWorkId, entity.getWorkId())
                    .eq(SettingEntryEntity::getSettingType, text(payload, "settingType"))
                    .eq(SettingEntryEntity::getName, text(payload, "name"))
                    .eq(SettingEntryEntity::getEntryStatus, "active")
                    .eq(SettingEntryEntity::getDeleted, 0).last("LIMIT 1"));
            return item == null ? null : item.getId();
        }
        if (TYPE_CHAPTER_SUMMARY.equals(entity.getCandidateType())) {
            ChapterSummaryEntity item = summaryMapper.selectOne(new LambdaQueryWrapper<ChapterSummaryEntity>()
                    .eq(ChapterSummaryEntity::getChapterId, entity.getChapterId())
                    .eq(ChapterSummaryEntity::getSummaryStatus, STATUS_CONFIRMED)
                    .eq(ChapterSummaryEntity::getDeleted, 0).last("LIMIT 1"));
            return item == null ? null : item.getId();
        }
        if (TYPE_KEY_EVENT.equals(entity.getCandidateType())) {
            ChapterKeyEventEntity item = eventMapper.selectOne(new LambdaQueryWrapper<ChapterKeyEventEntity>()
                    .eq(ChapterKeyEventEntity::getChapterId, entity.getChapterId())
                    .eq(ChapterKeyEventEntity::getEventTitle, text(payload, "title"))
                    .eq(ChapterKeyEventEntity::getDeleted, 0).last("LIMIT 1"));
            return item == null ? null : item.getId();
        }
        if (ACTION_SEED.equals(text(payload, FIELD_ACTION))) {
            ForeshadowingItemEntity item = foreshadowingMapper.selectOne(
                    new LambdaQueryWrapper<ForeshadowingItemEntity>()
                            .eq(ForeshadowingItemEntity::getWorkId, entity.getWorkId())
                            .eq(ForeshadowingItemEntity::getTitle, text(payload, "title"))
                            .eq(ForeshadowingItemEntity::getDeleted, 0).last("LIMIT 1"));
            return item == null ? null : item.getId();
        }
        return optionalLong(payload.get("existingForeshadowingId"));
    }

    private Target writeTarget(
            StoryKnowledgeCandidateEntity candidate,
            Map<String, Object> payload,
            String resolution,
            Long mergeTargetId) {
        return switch (candidate.getCandidateType()) {
            case TYPE_CHAPTER_SUMMARY -> writeSummary(candidate, payload, resolution, mergeTargetId);
            case TYPE_KEY_EVENT -> writeEvent(candidate, payload, resolution, mergeTargetId);
            case TYPE_SETTING -> writeSetting(candidate, payload, resolution, mergeTargetId);
            case TYPE_FORESHADOWING -> writeForeshadowing(candidate, payload, resolution, mergeTargetId);
            default -> throw invalid("未知知识候选类型");
        };
    }

    private Target writeSetting(
            StoryKnowledgeCandidateEntity candidate,
            Map<String, Object> payload,
            String resolution,
            Long mergeTargetId) {
        if (Set.of(RESOLUTION_MERGE, RESOLUTION_REPLACE).contains(resolution)) {
            SettingEntryEntity target = requireSetting(
                    conflictTarget(candidate, mergeTargetId), candidate.getWorkId());
            target.setSettingType(text(payload, "settingType"));
            target.setName(text(payload, "name"));
            target.setContent(text(payload, "content"));
            target.setVersion(version(target.getVersion()) + 1);
            settingMapper.updateById(target);
            return new Target(TYPE_SETTING, target.getId());
        }
        SettingEntryEntity target = new SettingEntryEntity();
        target.setWorkId(candidate.getWorkId());
        target.setSettingType(text(payload, "settingType"));
        target.setName(text(payload, "name"));
        target.setAliasesJson("[]");
        target.setContent(text(payload, "content"));
        target.setAttributesJson("{}");
        target.setSourceChapterId(candidate.getChapterId());
        target.setSourceCandidateId(candidate.getId());
        target.setEntryStatus("active");
        target.setDeleted(0);
        target.setVersion(0);
        settingMapper.insert(target);
        return new Target(TYPE_SETTING, target.getId());
    }

    private Target writeSummary(
            StoryKnowledgeCandidateEntity candidate,
            Map<String, Object> payload,
            String resolution,
            Long mergeTargetId) {
        ChapterSummaryEntity target = summaryMapper.selectOne(
                new LambdaQueryWrapper<ChapterSummaryEntity>()
                        .eq(ChapterSummaryEntity::getChapterId, candidate.getChapterId())
                        .eq(ChapterSummaryEntity::getDeleted, 0).last("LIMIT 1"));
        if (target != null && !RESOLUTION_REPLACE.equals(resolution)) {
            throw conflict("章节已有摘要，必须明确选择 replace");
        }
        if (target == null) {
            target = new ChapterSummaryEntity();
            target.setWorkId(candidate.getWorkId());
            target.setChapterId(candidate.getChapterId());
            target.setDeleted(0);
            target.setVersion(0);
        }
        target.setSummary(text(payload, "summary"));
        target.setCharacterChangesJson(json(list(payload, "characterChanges")));
        target.setOpenQuestionsJson(json(list(payload, "openQuestions")));
        target.setNewSettingsJson("[]");
        target.setNewForeshadowingJson("[]");
        target.setSummaryStatus(STATUS_CONFIRMED);
        target.setContentRevision(requireBatchById(candidate.getBatchId()).getSourceContentRevision());
        if (target.getId() == null) {
            summaryMapper.insert(target);
        } else {
            target.setVersion(version(target.getVersion()) + 1);
            summaryMapper.updateById(target);
        }
        return new Target(TYPE_CHAPTER_SUMMARY, target.getId());
    }

    private Target writeEvent(
            StoryKnowledgeCandidateEntity candidate,
            Map<String, Object> payload,
            String resolution,
            Long mergeTargetId) {
        if (Set.of(RESOLUTION_MERGE, RESOLUTION_REPLACE).contains(resolution)) {
            ChapterKeyEventEntity target = requireEvent(
                    conflictTarget(candidate, mergeTargetId), candidate.getWorkId());
            fillEvent(target, payload);
            target.setVersion(version(target.getVersion()) + 1);
            eventMapper.updateById(target);
            return new Target(TYPE_KEY_EVENT, target.getId());
        }
        ChapterKeyEventEntity target = new ChapterKeyEventEntity();
        target.setWorkId(candidate.getWorkId());
        target.setChapterId(candidate.getChapterId());
        fillEvent(target, payload);
        target.setDeleted(0);
        target.setVersion(0);
        eventMapper.insert(target);
        return new Target(TYPE_KEY_EVENT, target.getId());
    }

    private Target writeForeshadowing(
            StoryKnowledgeCandidateEntity candidate,
            Map<String, Object> payload,
            String resolution,
            Long mergeTargetId) {
        String action = text(payload, FIELD_ACTION);
        if (ACTION_SEED.equals(action)) {
            if (Set.of(RESOLUTION_MERGE, RESOLUTION_REPLACE).contains(resolution)) {
                ForeshadowingItemEntity target = requireForeshadowing(
                        conflictTarget(candidate, mergeTargetId), candidate.getWorkId());
                target.setTitle(text(payload, "title"));
                target.setDescription(text(payload, "description"));
                target.setSourceText(candidate.getEvidenceText());
                target.setSourceStartOffset(candidate.getEvidenceStartOffset());
                target.setSourceEndOffset(candidate.getEvidenceEndOffset());
                target.setVersion(version(target.getVersion()) + 1);
                foreshadowingMapper.updateById(target);
                return new Target(TYPE_FORESHADOWING, target.getId());
            }
            ForeshadowingItemEntity target = new ForeshadowingItemEntity();
            target.setWorkId(candidate.getWorkId());
            target.setSourceChapterId(candidate.getChapterId());
            target.setTitle(text(payload, "title"));
            target.setDescription(text(payload, "description"));
            target.setSourceText(candidate.getEvidenceText());
            target.setSourceStartOffset(candidate.getEvidenceStartOffset());
            target.setSourceEndOffset(candidate.getEvidenceEndOffset());
            target.setStatus("planted");
            target.setDeleted(0);
            target.setVersion(0);
            foreshadowingMapper.insert(target);
            return new Target(TYPE_FORESHADOWING, target.getId());
        }
        Long targetId = mergeTargetId == null
                ? optionalLong(payload.get("existingForeshadowingId")) : mergeTargetId;
        ForeshadowingItemEntity target = requireForeshadowing(targetId, candidate.getWorkId());
        target.setDescription(text(payload, "description"));
        if (ACTION_ADVANCE.equals(action)) {
            target.setStatus("pending_payoff");
        } else {
            target.setStatus("paid_off");
            target.setActualPayoffChapterId(candidate.getChapterId());
        }
        target.setVersion(version(target.getVersion()) + 1);
        foreshadowingMapper.updateById(target);
        return new Target(TYPE_FORESHADOWING, target.getId());
    }

    private void fillEvent(ChapterKeyEventEntity target, Map<String, Object> payload) {
        target.setEventTitle(text(payload, "title"));
        target.setEventContent(text(payload, "content"));
        target.setEventType(text(payload, "eventType"));
        target.setOccurredOrder(integer(payload.get("occurredOrder")));
        target.setRelatedSettingIdsJson(json(longList(payload, "relatedSettingIds")));
        target.setRelatedForeshadowingIdsJson(json(longList(payload, "relatedForeshadowingIds")));
    }

    private String normalizeResolution(
            StoryKnowledgeCandidateEntity candidate,
            String requested) {
        String resolution = StringUtils.hasText(requested) ? requested.trim() : RESOLUTION_CREATE;
        if (!Set.of(RESOLUTION_CREATE, RESOLUTION_MERGE, RESOLUTION_REPLACE).contains(resolution)) {
            throw invalid("resolution 取值非法");
        }
        if (STATUS_CONFLICT.equals(candidate.getCandidateStatus())
                && RESOLUTION_CREATE.equals(resolution)) {
            throw conflict("冲突候选必须明确选择 merge 或 replace");
        }
        return resolution;
    }

    private void ensureCurrent(StoryKnowledgeExtractionBatchEntity batch) {
        ChapterGenerationEntity generation = requireAcceptedGeneration(batch.getChapterId(), batch.getGenerationId());
        ChapterEntity chapter = requireChapter(batch.getChapterId());
        String current = chapter.getContent() == null ? "" : chapter.getContent();
        String currentFingerprint = fingerprint(generation.getId(), chapter.getVersion(), current);
        if (!currentFingerprint.equals(batch.getSourceFingerprint())) {
            staleMarker.mark(batch.getId());
            throw new BusinessException(ErrorCode.KNOWLEDGE_EXTRACTION_STALE,
                    "已采纳正文来源发生变化，知识提取批次已过期");
        }
    }

    private void updateTerminal(Long batchId, Set<String> from, String target, String errorCode) {
        batchMapper.update(null, new UpdateWrapper<StoryKnowledgeExtractionBatchEntity>()
                .eq("id", batchId).in("batch_status", from)
                .set("batch_status", target).set("error_code", errorCode)
                .setSql("version = version + 1"));
    }

    private BatchView view(StoryKnowledgeExtractionBatchEntity batch) {
        List<CandidateView> candidates = candidateMapper.selectList(
                        new LambdaQueryWrapper<StoryKnowledgeCandidateEntity>()
                                .eq(StoryKnowledgeCandidateEntity::getBatchId, batch.getId())
                                .eq(StoryKnowledgeCandidateEntity::getDeleted, 0)
                                .orderByAsc(StoryKnowledgeCandidateEntity::getId))
                .stream().map(this::candidateView).toList();
        return new BatchView(batch.getId(), batch.getWorkId(), batch.getChapterId(),
                batch.getGenerationId(), batch.getAiTaskId(), batch.getAgentRunId(),
                batch.getExtractorVersion(), batch.getSourceContentRevision(),
                batch.getSourceFingerprint(), batch.getBatchStatus(),
                batch.getCandidateCount(), batch.getErrorCode(), candidates,
                batch.getVersion(), batch.getGmtCreate(), batch.getGmtModified());
    }

    private CandidateView candidateView(StoryKnowledgeCandidateEntity item) {
        return new CandidateView(item.getId(), item.getBatchId(), item.getCandidateKey(),
                item.getCandidateType(), item.getCandidateStatus(), payload(item),
                new Evidence(item.getEvidenceStartOffset(), item.getEvidenceEndOffset(),
                        item.getEvidenceText()),
                item.getConflictTargetId(), item.getConfirmedTargetType(),
                item.getConfirmedTargetId(), item.getVersion(),
                item.getGmtCreate(), item.getGmtModified());
    }

    private CandidateDecision decision(StoryKnowledgeCandidateEntity item) {
        return new CandidateDecision(item.getId(), item.getCandidateStatus(),
                item.getConfirmedTargetType(), item.getConfirmedTargetId(),
                item.getVersion(), item.getGmtModified());
    }

    private Map<String, Object> payload(StoryKnowledgeCandidateEntity item) {
        try {
            return objectMapper.readValue(item.getPayloadJson(), new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "知识候选内容无法读取", exception);
        }
    }

    private String canonical(Map<String, Object> payload) {
        try {
            ObjectMapper canonicalMapper = objectMapper.copy()
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return canonicalMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "知识候选无法计算指纹", exception);
        }
    }

    private Long conflictTarget(StoryKnowledgeCandidateEntity candidate, Long requestedTargetId) {
        Long targetId = requestedTargetId == null
                ? candidate.getConflictTargetId() : requestedTargetId;
        if (targetId == null) {
            throw conflict("冲突候选缺少待合并的权威目标");
        }
        return targetId;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "知识提取数据无法序列化", exception);
        }
    }

    private ChapterGenerationEntity requireAcceptedGeneration(Long chapterId, Long generationId) {
        ChapterGenerationEntity generation =
                generationId == null ? null : generationMapper.selectById(generationId);
        boolean invalidGeneration = generation == null
                || Integer.valueOf(1).equals(generation.getDeleted())
                || !STATUS_ACCEPTED.equals(generation.getGenerationStatus());
        boolean chapterMismatch = generation != null && chapterId != null
                && !chapterId.equals(generation.getChapterId());
        if (invalidGeneration || chapterMismatch) {
            throw new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT,
                    "只有当前章节的已采纳正文能够触发知识提取");
        }
        return generation;
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private ChapterEntity requireChapterForUpdate(Long chapterId) {
        ChapterEntity chapter =
                chapterId == null ? null : chapterMapper.selectByIdForUpdate(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private StoryKnowledgeExtractionBatchEntity requireBatch(
            Long chapterId,
            Long generationId,
            Long batchId) {
        StoryKnowledgeExtractionBatchEntity batch = requireBatchById(batchId);
        if (!chapterId.equals(batch.getChapterId()) || !generationId.equals(batch.getGenerationId())) {
            throw notFound("知识提取批次不属于当前正文");
        }
        return batch;
    }

    private StoryKnowledgeExtractionBatchEntity requireBatchById(Long batchId) {
        StoryKnowledgeExtractionBatchEntity batch =
                batchId == null ? null : batchMapper.selectById(batchId);
        if (batch == null || Integer.valueOf(1).equals(batch.getDeleted())) {
            throw notFound("知识提取批次不存在");
        }
        return batch;
    }

    private StoryKnowledgeCandidateEntity requireCandidate(Long candidateId) {
        StoryKnowledgeCandidateEntity candidate =
                candidateId == null ? null : candidateMapper.selectById(candidateId);
        if (candidate == null || Integer.valueOf(1).equals(candidate.getDeleted())) {
            throw notFound("知识候选不存在");
        }
        return candidate;
    }

    private SettingEntryEntity requireSetting(Long id, Long workId) {
        SettingEntryEntity item = id == null ? null : settingMapper.selectById(id);
        if (item == null || Integer.valueOf(1).equals(item.getDeleted())
                || !workId.equals(item.getWorkId())) {
            throw invalid("设定引用不存在或跨作品");
        }
        return item;
    }

    private ForeshadowingItemEntity requireForeshadowing(Long id, Long workId) {
        ForeshadowingItemEntity item = id == null ? null : foreshadowingMapper.selectById(id);
        if (item == null || Integer.valueOf(1).equals(item.getDeleted())
                || !workId.equals(item.getWorkId())) {
            throw invalid("伏笔引用不存在或跨作品");
        }
        return item;
    }

    private ChapterKeyEventEntity requireEvent(Long id, Long workId) {
        ChapterKeyEventEntity item = id == null ? null : eventMapper.selectById(id);
        if (item == null || Integer.valueOf(1).equals(item.getDeleted())
                || !workId.equals(item.getWorkId())) {
            throw invalid("事件引用不存在或跨作品");
        }
        return item;
    }

    private void validateSettingIds(Map<String, Object> payload, String field, Long workId) {
        for (Long id : longList(payload, field)) {
            requireSetting(id, workId);
        }
    }

    private void validateForeshadowingIds(Map<String, Object> payload, String field, Long workId) {
        for (Long id : longList(payload, field)) {
            requireForeshadowing(id, workId);
        }
    }

    private String requiredAllowed(
            Map<String, Object> payload,
            String field,
            Set<String> allowed) {
        String value = text(payload, field);
        if (!allowed.contains(value)) {
            throw invalid(field + " 取值非法");
        }
        return value;
    }

    private String requiredText(Map<String, Object> payload, String field, int limit) {
        String value = text(payload, field);
        if (!StringUtils.hasText(value) || value.length() > limit) {
            throw invalid(field + " 为空或超过长度限制");
        }
        return value;
    }

    private void requiredPositiveInteger(Map<String, Object> payload, String field) {
        Integer value = integer(payload.get(field));
        if (value == null || value < 0) {
            throw invalid(field + " 必须为非负整数");
        }
    }

    private void optionalStringList(Map<String, Object> payload, String field, int limit) {
        List<?> values = list(payload, field);
        boolean invalidSize = values.size() > limit;
        boolean invalidItem = values.stream().anyMatch(value ->
                !(value instanceof String text) || !StringUtils.hasText(text)
                        || text.length() > MAX_TITLE_LENGTH);
        if (invalidSize || invalidItem) {
            throw invalid(field + " 不符合字符串数组契约");
        }
    }

    private List<?> list(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw invalid(field + " 必须为数组");
        }
        return values;
    }

    private List<Long> longList(Map<String, Object> payload, String field) {
        List<Long> result = new ArrayList<>();
        for (Object value : list(payload, field)) {
            Long id = optionalLong(value);
            if (id == null) {
                throw invalid(field + " 只能包含数字 ID");
            }
            result.add(id);
        }
        return result;
    }

    private String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value instanceof String text ? text.trim() : "";
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Long optionalLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private int candidateCount(Long batchId) {
        return Math.toIntExact(candidateMapper.selectCount(
                new LambdaQueryWrapper<StoryKnowledgeCandidateEntity>()
                        .eq(StoryKnowledgeCandidateEntity::getBatchId, batchId)
                        .eq(StoryKnowledgeCandidateEntity::getDeleted, 0)));
    }

    private String automaticKey(Long generationId) {
        return "accepted-generation:" + generationId + ":" + EXTRACTOR_VERSION;
    }

    private String fingerprint(Long generationId, Integer revision, String content) {
        return hash(generationId + ":" + revision + ":" + content);
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算知识提取指纹", exception);
        }
    }

    private int version(Integer value) {
        return value == null ? 0 : value;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.KNOWLEDGE_EXTRACTION_INVALID, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.KNOWLEDGE_EXTRACTION_CONFLICT, message);
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.KNOWLEDGE_EXTRACTION_NOT_FOUND, message);
    }

    private record Target(String type, Long id) {
    }
}
