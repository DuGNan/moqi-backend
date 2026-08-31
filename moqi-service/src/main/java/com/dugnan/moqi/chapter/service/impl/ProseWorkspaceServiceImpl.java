package com.dugnan.moqi.chapter.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.CreateEvaluationRequest;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.FormalProseView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.AdoptProseCandidateRequest;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ComparisonSide;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseCandidateAdoptionView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseCandidateBasisView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseComparisonView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseCandidateDetail;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseCandidateSummary;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseWorkspaceView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.QualitySummary;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.RunningTaskSummary;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.SaveProseCandidateRequest;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.SaveWorkspaceSelectionRequest;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.WorkspaceSelectionView;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.entity.ChapterProseWorkspaceSelectionEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseWorkspaceSelectionMapper;
import com.dugnan.moqi.chapter.selection.ProsePlanningChangeService;
import com.dugnan.moqi.chapter.selection.ProseProposalSettlementService;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.chapter.service.GenerationRetryMetadataResolver;
import com.dugnan.moqi.chapter.service.GenerationRetryMetadataResolver.RetryMetadata;
import com.dugnan.moqi.chapter.service.ProseCandidateMaterializationService;
import com.dugnan.moqi.chapter.service.ProseCandidateAdoptionService;
import com.dugnan.moqi.chapter.service.ProseWorkspaceService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.api.PublicFailureFactory;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.sourcechain.entity.ChapterAssetSourceSnapshotEntity;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 实现统一章节正文目录、稳定对象选择和候选显式保存。
 */
@Service
public class ProseWorkspaceServiceImpl implements ProseWorkspaceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProseWorkspaceServiceImpl.class);
    private static final String OBJECT_FORMAL = "formal";
    private static final String OBJECT_CANDIDATE = "candidate";
    private static final String CANDIDATE_PREFIX = "candidate:";
    private static final String SNAPSHOT_STATUS = "candidate_snapshot";
    private static final String EVALUATION_STEP = "semantic_evaluate";
    private static final String EVALUATION_WORKFLOW = "chapter_generation_evaluation_v1";

    private final ChapterMapper chapterMapper;
    private final ChapterGenerationMapper generationMapper;
    private final ChapterGenerationEvaluationReportMapper reportMapper;
    private final ChapterProseCandidateMapper candidateMapper;
    private final ChapterProseWorkspaceSelectionMapper selectionMapper;
    private final ChapterAssetSourceSnapshotMapper sourceSnapshotMapper;
    private final GenerationEvaluationService evaluationService;
    private final GenerationRetryMetadataResolver retryMetadataResolver;
    private final ProseCandidateMaterializationService materializationService;
    private ProsePlanningChangeService planningChangeService;
    private ProseProposalSettlementService proposalSettlementService;
    private ProseCandidateAdoptionService adoptionService;
    private ObjectMapper objectMapper;

    public ProseWorkspaceServiceImpl(
            ChapterMapper chapterMapper,
            ChapterGenerationMapper generationMapper,
            ChapterGenerationEvaluationReportMapper reportMapper,
            ChapterProseCandidateMapper candidateMapper,
            ChapterProseWorkspaceSelectionMapper selectionMapper,
            ChapterAssetSourceSnapshotMapper sourceSnapshotMapper,
            GenerationEvaluationService evaluationService,
            GenerationRetryMetadataResolver retryMetadataResolver,
            ProseCandidateMaterializationService materializationService) {
        this.chapterMapper = chapterMapper;
        this.generationMapper = generationMapper;
        this.reportMapper = reportMapper;
        this.candidateMapper = candidateMapper;
        this.selectionMapper = selectionMapper;
        this.sourceSnapshotMapper = sourceSnapshotMapper;
        this.evaluationService = evaluationService;
        this.retryMetadataResolver = retryMetadataResolver;
        this.materializationService = materializationService;
    }

    @Autowired
    public void setPlanningChangeService(ProsePlanningChangeService planningChangeService) {
        this.planningChangeService = planningChangeService;
    }

    @Autowired
    public void setProposalSettlementService(ProseProposalSettlementService proposalSettlementService) {
        this.proposalSettlementService = proposalSettlementService;
    }

    @Autowired
    public void setAdoptionService(ProseCandidateAdoptionService adoptionService) {
        this.adoptionService = adoptionService;
    }

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ProseWorkspaceView getWorkspace(Long chapterId) {
        ChapterEntity chapter = requireChapter(chapterId);
        List<ChapterProseCandidateEntity> entities = candidates(chapterId);
        java.util.Map<Long, Integer> displayNumbers = displayNumbers(entities);
        List<ProseCandidateSummary> candidates = entities.stream()
                .map(item -> summary(item, displayNumbers.get(item.getId())))
                .toList();
        ChapterProseWorkspaceSelectionEntity selection = findSelection(chapterId);
        return new ProseWorkspaceView(
                chapterId,
                formal(chapter),
                candidates,
                selection == null ? defaultSelection(chapter) : selectionView(selection),
                runningTasks(chapterId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public WorkspaceSelectionView saveSelection(Long chapterId, SaveWorkspaceSelectionRequest request) {
        ChapterEntity chapter = requireLockedChapter(chapterId);
        validateSelection(chapterId, request);
        ChapterProseWorkspaceSelectionEntity existing = findSelection(chapterId);
        if (existing == null) {
            if (!Integer.valueOf(0).equals(request.baseVersion())) {
                throw conflict(ErrorCode.PROSE_WORKSPACE_CONFLICT, "工作区选择版本已变化，请刷新后重试");
            }
            ChapterProseWorkspaceSelectionEntity created = new ChapterProseWorkspaceSelectionEntity();
            created.setWorkId(chapter.getWorkId());
            created.setChapterId(chapterId);
            created.setSelectedObjectKind(request.objectKind());
            created.setSelectedObjectId(request.objectId());
            created.setDeleted(0);
            created.setVersion(0);
            selectionMapper.insert(created);
            return selectionView(selectionMapper.selectById(created.getId()));
        }
        if (Objects.equals(existing.getVersion(), request.baseVersion() + 1)
                && Objects.equals(existing.getSelectedObjectKind(), request.objectKind())
                && Objects.equals(existing.getSelectedObjectId(), request.objectId())) {
            return selectionView(existing);
        }
        if (selectionMapper.updateSelectionIfVersion(
                chapterId, request.objectKind(), request.objectId(), request.baseVersion()) != 1) {
            throw conflict(ErrorCode.PROSE_WORKSPACE_CONFLICT, "工作区选择版本已变化，请刷新后重试");
        }
        return selectionView(findSelection(chapterId));
    }

    @Override
    public ProseCandidateDetail getCandidate(Long chapterId, Long candidateId) {
        requireChapter(chapterId);
        return detail(requireCandidate(chapterId, candidateId), displayNo(chapterId, candidateId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ProseCandidateDetail saveCandidate(
            Long chapterId,
            Long candidateId,
            SaveProseCandidateRequest request) {
        requireChapter(chapterId);
        if (request == null || request.content() == null || request.baseVersion() == null) {
            throw badRequest("content 和 baseVersion 不能为空");
        }
        boolean hasPlanningPackage = request.planningChangePackageId() != null;
        boolean isPlanningConfirmed = Boolean.TRUE.equals(request.planningConfirmed());
        if (hasPlanningPackage != isPlanningConfirmed) {
            throw badRequest("规划联动保存必须同时提交 planningChangePackageId 和 planningConfirmed=true");
        }
        ChapterProseCandidateEntity candidate = candidateMapper.selectByIdForUpdate(chapterId, candidateId);
        if (candidate == null) {
            throw conflict(ErrorCode.PROSE_CANDIDATE_NOT_FOUND, "正文候选不存在");
        }
        List<Long> appliedProposalIds = request.appliedProposalIds() == null
                ? List.of() : request.appliedProposalIds();
        String contentHash = hash(request.content());
        if (Objects.equals(candidate.getVersion(), request.baseVersion() + 1)
                && Objects.equals(candidate.getContentHash(), contentHash)) {
            proposalSettlementService.requireApplied(chapterId, candidateId, appliedProposalIds,
                    candidate.getVersion(), contentHash);
            if (hasPlanningPackage) {
                planningChangeService.requireApplied(chapterId, candidateId, request.planningChangePackageId(),
                        candidate.getVersion(), contentHash, appliedProposalIds);
            }
            scheduleEvaluationAfterCommit(chapterId, candidateId, candidate.getVersion(),
                    candidate.getQualityGenerationId(), contentHash);
            return detail(candidate);
        }
        if (!Objects.equals(candidate.getVersion(), request.baseVersion())) {
            throw candidateVersionConflict(candidate);
        }
        proposalSettlementService.validateForSave(chapterId, candidate, appliedProposalIds);
        ChapterGenerationEntity source = requireQualitySource(candidate);
        ChapterGenerationEntity snapshot = qualitySnapshot(
                source, candidateId, request.baseVersion() + 1, request.content(), contentHash);
        generationMapper.insert(snapshot);
        snapshot.setSourceSnapshotId(copySourceSnapshot(source, snapshot));
        if (snapshot.getSourceSnapshotId() != null) {
            generationMapper.updateById(snapshot);
        }
        if (hasPlanningPackage) {
            planningChangeService.apply(chapterId, candidate, request.planningChangePackageId(),
                    request.baseVersion() + 1, contentHash, appliedProposalIds);
        }
        proposalSettlementService.markApplied(chapterId, candidate, appliedProposalIds,
                request.baseVersion() + 1, contentHash);
        if (candidateMapper.updateContentIfVersion(
                chapterId,
                candidateId,
                request.content(),
                contentHash,
                wordCount(request.content()),
                snapshot.getId(),
                request.baseVersion()) != 1) {
            throw candidateVersionConflict(requireCandidate(chapterId, candidateId));
        }
        scheduleEvaluationAfterCommit(chapterId, candidateId, request.baseVersion() + 1, snapshot.getId(), contentHash);
        return detail(requireCandidate(chapterId, candidateId));
    }

    @Override
    public ProseCandidateBasisView getCandidateBasis(Long chapterId, Long candidateId) {
        requireChapter(chapterId);
        ChapterProseCandidateEntity candidate = requireCandidate(chapterId, candidateId);
        return basisView(requireBasisSource(candidate.getSourceGenerationId()), candidate.getContentHash());
    }

    @Override
    public ProseCandidateBasisView getObjectBasis(Long chapterId, String objectId) {
        ChapterEntity chapter = requireChapter(chapterId);
        if (formalId(chapterId).equals(objectId)) {
            return basisView(requireBasisSource(chapter.getFormalSourceGenerationId()),
                    hash(Objects.requireNonNullElse(chapter.getContent(), "")));
        }
        Long candidateId = parseCandidateId(objectId);
        ChapterProseCandidateEntity candidate = requireCandidate(chapterId, candidateId);
        return basisView(requireBasisSource(candidate.getSourceGenerationId()), candidate.getContentHash());
    }

    private ChapterGenerationEntity requireBasisSource(Long sourceGenerationId) {
        ChapterGenerationEntity source = sourceGenerationId == null
                ? null : generationMapper.selectById(sourceGenerationId);
        if (source == null || Integer.valueOf(1).equals(source.getDeleted())) {
            throw conflict(ErrorCode.PROSE_CANDIDATE_CONFLICT, "当前正文对象缺少可追溯的生成依据");
        }
        return source;
    }

    private ProseCandidateBasisView basisView(ChapterGenerationEntity source, String currentContentHash) {
        JsonNode basis = readBasis(source);
        JsonNode brief = basis.path("chapterGenerationBrief");
        boolean complete = brief.isObject() && List.of("chapterPurpose", "chapterGoal", "coreConflict",
                "openingConditions", "requiredEndingState", "eventCausality", "stateChanges",
                "characterConstraints", "entityExplanations", "creativeFreedom", "prohibitedInventions")
                .stream().allMatch(brief::has);
        String sourceHash = hash(source.getGeneratedContent());
        return new ProseCandidateBasisView(complete ? "complete" : "legacy_limited",
                !Objects.equals(sourceHash, currentContentHash), source.getId(), sourceHash,
                currentContentHash, basisOutline(brief), basisScenes(brief), authorValue(brief,
                        "characterConstraints"), previousProse(basis), worldSettings(brief), basisConstraints(brief));
    }

    @Override
    public ProseComparisonView compare(Long chapterId, String leftObjectId, String rightObjectId) {
        ChapterEntity chapter = requireChapter(chapterId);
        return new ProseComparisonView(comparisonSide(chapter, leftObjectId),
                comparisonSide(chapter, rightObjectId));
    }

    @Override
    public ProseCandidateAdoptionView adoptCandidate(
            Long chapterId,
            Long candidateId,
            AdoptProseCandidateRequest request) {
        return adoptionService.adopt(chapterId, candidateId, request);
    }

    private void scheduleEvaluationAfterCommit(
            Long chapterId,
            Long candidateId,
            Integer contentVersion,
            Long generationId,
            String contentHash) {
        Runnable schedule = () -> {
            try {
                evaluationService.create(chapterId, generationId, new CreateEvaluationRequest(
                        null, "candidate:" + candidateId + ":" + contentVersion + ":" + contentHash));
                materializationService.markQualityRequested(generationId);
            } catch (RuntimeException exception) {
                materializationService.markQualityUnavailable(generationId);
                LOGGER.error("正文候选自动评价启动失败，candidateId={}, contentVersion={}",
                        candidateId, contentVersion, exception);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule.run();
                }
            });
        } else {
            schedule.run();
        }
    }

    private ChapterGenerationEntity qualitySnapshot(
            ChapterGenerationEntity source,
            Long candidateId,
            Integer contentVersion,
            String content,
            String contentHash) {
        ChapterGenerationEntity snapshot = new ChapterGenerationEntity();
        snapshot.setWorkId(source.getWorkId());
        snapshot.setChapterId(source.getChapterId());
        snapshot.setBriefId(source.getBriefId());
        snapshot.setOutlineId(source.getOutlineId());
        snapshot.setOutlineRevision(source.getOutlineRevision());
        snapshot.setChapterPlanVersionId(source.getChapterPlanVersionId());
        snapshot.setBaseGenerationId(source.getId());
        snapshot.setGenerationStatus(SNAPSHOT_STATUS);
        snapshot.setGenerationMode(source.getGenerationMode());
        snapshot.setSelectionMode(source.getSelectionMode());
        snapshot.setIdempotencyKey("prose-candidate:" + candidateId + ":" + contentVersion + ":" + contentHash);
        snapshot.setLengthPreset(source.getLengthPreset());
        snapshot.setCustomWordCount(source.getCustomWordCount());
        snapshot.setBasisSnapshotJson(source.getBasisSnapshotJson());
        snapshot.setExecutionConfigJson(source.getExecutionConfigJson());
        snapshot.setGeneratedContent(content);
        snapshot.setContentAssemblyMode("candidate_saved");
        snapshot.setCohesionStatus(source.getCohesionStatus());
        snapshot.setGenerationTemplateVersion("prose-candidate-save-v1");
        snapshot.setGenerationFinishReason("explicit_save");
        snapshot.setWordCount(wordCount(content));
        snapshot.setValidityStatus(source.getValidityStatus());
        snapshot.setValidityReasonCodesJson(source.getValidityReasonCodesJson());
        snapshot.setDeleted(0);
        snapshot.setVersion(0);
        return snapshot;
    }

    private Long copySourceSnapshot(ChapterGenerationEntity source, ChapterGenerationEntity target) {
        if (source.getSourceSnapshotId() == null) {
            return null;
        }
        ChapterAssetSourceSnapshotEntity original = sourceSnapshotMapper.selectById(source.getSourceSnapshotId());
        if (original == null || Integer.valueOf(1).equals(original.getDeleted())
                || !Objects.equals(source.getWorkId(), original.getWorkId())
                || !Objects.equals(source.getChapterId(), original.getChapterId())
                || !"generation".equals(original.getAssetType())
                || !Objects.equals(source.getId(), original.getAssetId())) {
            throw conflict(ErrorCode.PROSE_CANDIDATE_CONFLICT, "正文候选的来源快照不存在或归属不一致");
        }
        ChapterAssetSourceSnapshotEntity snapshot = new ChapterAssetSourceSnapshotEntity();
        snapshot.setWorkId(target.getWorkId());
        snapshot.setChapterId(target.getChapterId());
        snapshot.setAssetType("generation");
        snapshot.setAssetId(target.getId());
        snapshot.setAssetVersion(0);
        snapshot.setSourceConsensusVersionId(original.getSourceConsensusVersionId());
        snapshot.setSourceNarrativePlanVersionId(original.getSourceNarrativePlanVersionId());
        snapshot.setSourceOutlineId(original.getSourceOutlineId());
        snapshot.setSourceOutlineRevision(original.getSourceOutlineRevision());
        snapshot.setSourceScenePlanVersionId(original.getSourceScenePlanVersionId());
        snapshot.setSourceContextSnapshotId(original.getSourceContextSnapshotId());
        snapshot.setSourceContentHash(original.getSourceContentHash());
        snapshot.setDeleted(0);
        snapshot.setVersion(0);
        sourceSnapshotMapper.insert(snapshot);
        return snapshot.getId();
    }

    private ChapterGenerationEntity requireQualitySource(ChapterProseCandidateEntity candidate) {
        Long generationId = candidate.getQualityGenerationId() == null
                ? candidate.getSourceGenerationId() : candidate.getQualityGenerationId();
        ChapterGenerationEntity source = generationId == null ? null : generationMapper.selectById(generationId);
        if (source == null || Integer.valueOf(1).equals(source.getDeleted())) {
            throw conflict(ErrorCode.PROSE_CANDIDATE_CONFLICT, "正文候选缺少可评价的来源快照");
        }
        return source;
    }

    private FormalProseView formal(ChapterEntity chapter) {
        String content = Objects.requireNonNullElse(chapter.getContent(), "");
        return new FormalProseView(
                formalId(chapter.getId()),
                content,
                hash(content),
                chapter.getVersion(),
                wordCount(content),
                chapter.getCurrentProseRevisionId() == null,
                chapter.getCurrentProseRevisionId(),
                chapter.getGmtModified());
    }

    private List<ChapterProseCandidateEntity> candidates(Long chapterId) {
        return candidateMapper.selectList(new LambdaQueryWrapper<ChapterProseCandidateEntity>()
                .eq(ChapterProseCandidateEntity::getChapterId, chapterId)
                .eq(ChapterProseCandidateEntity::getDeleted, 0)
                .orderByDesc(ChapterProseCandidateEntity::getId));
    }

    private List<RunningTaskSummary> runningTasks(Long chapterId) {
        return generationMapper.selectList(new LambdaQueryWrapper<ChapterGenerationEntity>()
                        .eq(ChapterGenerationEntity::getChapterId, chapterId)
                        .in(ChapterGenerationEntity::getGenerationStatus, List.of("queued", "running"))
                        .eq(ChapterGenerationEntity::getDeleted, 0)
                        .orderByDesc(ChapterGenerationEntity::getGmtModified))
                .stream()
                .map(item -> new RunningTaskSummary(item.getId(), item.getGenerationStatus(),
                        Objects.requireNonNullElse(item.getContentAssemblyMode(), "generation"), item.getGmtModified()))
                .toList();
    }

    private ProseCandidateSummary summary(ChapterProseCandidateEntity item, Integer displayNo) {
        return new ProseCandidateSummary(
                item.getId(), candidateId(item.getId()), rootId(item), item.getParentCandidateId(), item.getSourceKind(),
                item.getCandidateStatus(), item.getAdoptionStatus(), item.getVersion(), item.getContentHash(),
                item.getWordCount(), quality(item), adoptionService.readiness(item),
                item.getGmtCreate(), item.getGmtModified(), displayNo);
    }

    private ProseCandidateDetail detail(ChapterProseCandidateEntity item) {
        return detail(item, displayNo(item.getChapterId(), item.getId()));
    }

    private ProseCandidateDetail detail(ChapterProseCandidateEntity item, Integer displayNo) {
        return new ProseCandidateDetail(
                item.getChapterId(), item.getId(), candidateId(item.getId()), rootId(item), item.getParentCandidateId(),
                item.getSourceKind(), item.getCandidateStatus(), item.getAdoptionStatus(), item.getContent(),
                item.getVersion(), item.getContentHash(), item.getWordCount(), quality(item),
                adoptionService.readiness(item),
                item.getGmtCreate(), item.getGmtModified(), displayNo);
    }

    private java.util.Map<Long, Integer> displayNumbers(List<ChapterProseCandidateEntity> entities) {
        java.util.Map<Long, Integer> result = new java.util.HashMap<>();
        List<ChapterProseCandidateEntity> ordered = entities.stream()
                .sorted(java.util.Comparator.comparing(ChapterProseCandidateEntity::getId))
                .toList();
        for (int index = 0; index < ordered.size(); index++) {
            result.put(ordered.get(index).getId(), index + 1);
        }
        return result;
    }

    private Integer displayNo(Long chapterId, Long candidateId) {
        return displayNumbers(candidates(chapterId)).get(candidateId);
    }

    private ComparisonSide comparisonSide(ChapterEntity chapter, String objectId) {
        if (formalId(chapter.getId()).equals(objectId)) {
            String content = Objects.requireNonNullElse(chapter.getContent(), "");
            return new ComparisonSide(OBJECT_FORMAL, objectId, content, chapter.getVersion(), hash(content),
                    wordCount(content), null, null, "formal", null, null, chapter.getGmtModified());
        }
        Long parsedId = parseCandidateId(objectId);
        ChapterProseCandidateEntity candidate = requireCandidate(chapter.getId(), parsedId);
        return new ComparisonSide(OBJECT_CANDIDATE, candidateId(candidate.getId()), candidate.getContent(),
                candidate.getVersion(), candidate.getContentHash(), candidate.getWordCount(), rootId(candidate),
                candidate.getParentCandidateId(), candidate.getSourceKind(), candidate.getSourceGenerationId(),
                candidate.getSourceBoundedRevisionId(), candidate.getGmtModified());
    }

    private JsonNode readBasis(ChapterGenerationEntity source) {
        try {
            return StringUtils.hasText(source.getBasisSnapshotJson())
                    ? objectMapper.readTree(source.getBasisSnapshotJson()) : objectMapper.createObjectNode();
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成依据快照暂时无法读取", exception);
        }
    }

    private ObjectNode basisOutline(JsonNode brief) {
        return selectedObject(brief, "chapterPurpose", "chapterGoal", "coreConflict",
                "openingConditions", "requiredEndingState");
    }

    private ObjectNode basisScenes(JsonNode brief) {
        return selectedObject(brief, "eventCausality", "stateChanges");
    }

    private ObjectNode basisConstraints(JsonNode brief) {
        return selectedObject(brief, "creativeFreedom", "prohibitedInventions");
    }

    private ObjectNode selectedObject(JsonNode source, String... fields) {
        ObjectNode result = objectMapper.createObjectNode();
        for (String field : fields) {
            if (source.has(field)) {
                result.set(field, source.get(field).deepCopy());
            }
        }
        return result;
    }

    private JsonNode authorValue(JsonNode source, String field) {
        return source.has(field) ? source.get(field).deepCopy() : objectMapper.createArrayNode();
    }

    private ObjectNode previousProse(JsonNode basis) {
        JsonNode frozen = basis.path("currentProseBasis").isObject()
                ? basis.path("currentProseBasis") : basis.path("baseGeneration");
        return selectedObject(frozen, "content", "contentHash");
    }

    private ArrayNode worldSettings(JsonNode brief) {
        ArrayNode result = objectMapper.createArrayNode();
        JsonNode entities = brief.path("entityExplanations");
        if (!entities.isArray()) {
            return result;
        }
        for (JsonNode entity : entities) {
            if (entity.isObject()) {
                result.add(selectedObject(entity, "type", "name", "explanation"));
            } else if (entity.isTextual()) {
                result.add(entity.textValue());
            }
        }
        return result;
    }

    private QualitySummary quality(ChapterProseCandidateEntity candidate) {
        if (candidate.getQualityGenerationId() == null) {
            return new QualitySummary("unavailable", null, candidate.getContentHash(), null,
                    null, null, null, false, null);
        }
        List<ChapterGenerationEvaluationReportEntity> reports = reportMapper.selectList(
                new LambdaQueryWrapper<ChapterGenerationEvaluationReportEntity>()
                        .eq(ChapterGenerationEvaluationReportEntity::getGenerationId, candidate.getQualityGenerationId())
                        .isNull(ChapterGenerationEvaluationReportEntity::getGenerationSceneId)
                        .eq(ChapterGenerationEvaluationReportEntity::getDeleted, 0)
                        .orderByDesc(ChapterGenerationEvaluationReportEntity::getId)
                        .last("LIMIT 1"));
        if (reports.isEmpty()) {
            String requestStatus = "pending".equals(candidate.getQualityRequestStatus())
                    ? "pending" : "unavailable";
            return new QualitySummary(requestStatus, null, candidate.getContentHash(), null,
                    candidate.getQualityGenerationId(), null, null, false, null);
        }
        ChapterGenerationEvaluationReportEntity report = reports.get(0);
        String status = switch (Objects.requireNonNullElse(report.getReportStatus(), "")) {
            case "queued", "running" -> "evaluating";
            case "ready" -> "ready";
            case "failed" -> "failed";
            default -> "unavailable";
        };
        boolean exactReport = Objects.equals(candidate.getQualityGenerationId(), report.getGenerationId())
                && Objects.equals(candidate.getWorkId(), report.getWorkId())
                && Objects.equals(candidate.getChapterId(), report.getChapterId())
                && Objects.equals(candidate.getContentHash(), report.getContentHash());
        RetryMetadata resolved = "failed".equals(status) && exactReport
                ? retryMetadataResolver.resolveOwned(report.getAgentRunId(), EVALUATION_STEP,
                        EVALUATION_WORKFLOW, report.getWorkId(), report.getChapterId(), report.getAiTaskId())
                : RetryMetadata.empty();
        RetryMetadata metadata = resolved == null ? RetryMetadata.empty() : resolved;
        boolean retryable = EVALUATION_STEP.equals(metadata.currentStepKey())
                && Boolean.TRUE.equals(metadata.retryable());
        String failureDescription = "failed".equals(status)
                ? safeFailureDescription(report.getErrorCode(), report.getErrorMessage()) : null;
        return new QualitySummary(status, "ready".equals(status) ? report.getConclusion() : null,
                report.getContentHash(), report.getGmtModified(), candidate.getQualityGenerationId(),
                report.getId(), retryable ? metadata.currentAttempt() : null, retryable, failureDescription);
    }

    private String safeFailureDescription(String errorCode, String errorMessage) {
        return StringUtils.hasText(errorCode)
                ? PublicFailureFactory.safeMessage(errorCode, errorMessage)
                : "评价未能完成，请稍后重试";
    }

    private void validateSelection(Long chapterId, SaveWorkspaceSelectionRequest request) {
        if (request == null || !StringUtils.hasText(request.objectKind())
                || !StringUtils.hasText(request.objectId()) || request.baseVersion() == null) {
            throw badRequest("objectKind、objectId 和 baseVersion 不能为空");
        }
        if (OBJECT_FORMAL.equals(request.objectKind())) {
            if (!formalId(chapterId).equals(request.objectId())) {
                throw badRequest("正式正文对象 ID 与章节不匹配");
            }
            return;
        }
        if (!OBJECT_CANDIDATE.equals(request.objectKind())) {
            throw badRequest("objectKind 仅支持 formal 或 candidate");
        }
        Long candidateId = parseCandidateId(request.objectId());
        requireCandidate(chapterId, candidateId);
    }

    private Long parseCandidateId(String objectId) {
        try {
            if (!objectId.startsWith(CANDIDATE_PREFIX)) {
                throw new NumberFormatException();
            }
            return Long.valueOf(objectId.substring(CANDIDATE_PREFIX.length()));
        } catch (NumberFormatException exception) {
            throw badRequest("候选对象 ID 格式不正确");
        }
    }

    private ChapterProseWorkspaceSelectionEntity findSelection(Long chapterId) {
        return selectionMapper.selectOne(new LambdaQueryWrapper<ChapterProseWorkspaceSelectionEntity>()
                .eq(ChapterProseWorkspaceSelectionEntity::getChapterId, chapterId)
                .eq(ChapterProseWorkspaceSelectionEntity::getDeleted, 0));
    }

    private WorkspaceSelectionView defaultSelection(ChapterEntity chapter) {
        return new WorkspaceSelectionView(OBJECT_FORMAL, formalId(chapter.getId()), 0, chapter.getGmtModified());
    }

    private WorkspaceSelectionView selectionView(ChapterProseWorkspaceSelectionEntity selection) {
        return new WorkspaceSelectionView(selection.getSelectedObjectKind(), selection.getSelectedObjectId(),
                selection.getVersion(), selection.getGmtModified());
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw conflict(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private ChapterEntity requireLockedChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectByIdForUpdate(chapterId);
        if (chapter == null) {
            throw conflict(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private ChapterProseCandidateEntity requireCandidate(Long chapterId, Long candidateId) {
        ChapterProseCandidateEntity candidate = candidateId == null ? null : candidateMapper.selectOne(
                new LambdaQueryWrapper<ChapterProseCandidateEntity>()
                        .eq(ChapterProseCandidateEntity::getId, candidateId)
                        .eq(ChapterProseCandidateEntity::getChapterId, chapterId)
                        .eq(ChapterProseCandidateEntity::getDeleted, 0));
        if (candidate == null) {
            throw conflict(ErrorCode.PROSE_CANDIDATE_NOT_FOUND, "正文候选不存在");
        }
        return candidate;
    }

    private BusinessException candidateVersionConflict(ChapterProseCandidateEntity serverCandidate) {
        return conflict(ErrorCode.PROSE_CANDIDATE_CONFLICT,
                "正文候选已被更新，当前服务端版本为 " + serverCandidate.getVersion());
    }

    private Long rootId(ChapterProseCandidateEntity candidate) {
        return Objects.requireNonNullElse(candidate.getRootCandidateId(), candidate.getId());
    }

    private String formalId(Long chapterId) {
        return "formal:" + chapterId;
    }

    private String candidateId(Long candidateId) {
        return CANDIDATE_PREFIX + candidateId;
    }

    private int wordCount(String content) {
        return content == null ? 0 : content.codePointCount(0, content.length());
    }

    private String hash(String content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNullElse(content, "").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算正文内容哈希", exception);
        }
    }

    private BusinessException badRequest(String message) {
        return conflict(ErrorCode.BAD_REQUEST, message);
    }

    private BusinessException conflict(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }
}
