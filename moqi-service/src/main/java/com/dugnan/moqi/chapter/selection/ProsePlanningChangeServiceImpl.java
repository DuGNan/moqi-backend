package com.dugnan.moqi.chapter.selection;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;
import com.dugnan.moqi.chapter.entity.ProsePlanningChangePackageEntity;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ProsePlanningChangePackageMapper;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ModelPlanningProposal;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.PlanningContext;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.PlanningChangePackageView;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.PlanningContentCodec;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 持久化规划候选，并以大纲和场景规划双 CAS 原子发布新权威版本。
 */
@Service
public class ProsePlanningChangeServiceImpl implements ProsePlanningChangeService {

    private static final Set<String> PACKAGE_SOURCE_STATUSES = Set.of("running", "ready", "review_required");
    private static final String PACKAGE_CANDIDATE = "candidate";
    private static final String PACKAGE_APPLIED = "applied";
    private static final String OPERATION_DISCUSS = "discuss";
    private static final String LOCAL_USER = "local-user";
    private static final String MODEL_PACKAGE_KEY_PREFIX = "assistance-model:";
    private static final int MAX_SUMMARY_LENGTH = 1000;

    private final ChapterSelectionAssistanceMapper assistanceMapper;
    private final ChapterProseCandidateMapper candidateMapper;
    private final ProsePlanningChangePackageMapper packageMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final ChapterPlanVersionMapper planMapper;
    private final ScenePlanVersionMapper sceneMapper;
    private final PlanningContentCodec planningCodec;
    private final ObjectMapper objectMapper;

    public ProsePlanningChangeServiceImpl(
            ChapterSelectionAssistanceMapper assistanceMapper,
            ChapterProseCandidateMapper candidateMapper,
            ProsePlanningChangePackageMapper packageMapper,
            ChapterOutlineQueryMapper outlineMapper,
            ChapterPlanVersionMapper planMapper,
            ScenePlanVersionMapper sceneMapper,
            PlanningContentCodec planningCodec,
            ObjectMapper objectMapper) {
        this.assistanceMapper = assistanceMapper;
        this.candidateMapper = candidateMapper;
        this.packageMapper = packageMapper;
        this.outlineMapper = outlineMapper;
        this.planMapper = planMapper;
        this.sceneMapper = sceneMapper;
        this.planningCodec = planningCodec;
        this.objectMapper = objectMapper;
    }

    @Override
    public PlanningContext freezeContext(Long chapterId) {
        ChapterOutlineEntity outline = outlineMapper.findLatest(chapterId);
        ChapterPlanVersionEntity plan = planMapper.selectOne(new LambdaQueryWrapper<ChapterPlanVersionEntity>()
                .eq(ChapterPlanVersionEntity::getChapterId, chapterId)
                .eq(ChapterPlanVersionEntity::getCurrentMarker, 1)
                .eq(ChapterPlanVersionEntity::getDeleted, 0));
        if (outline == null || plan == null) {
            return null;
        }
        if (!Objects.equals(plan.getOutlineId(), outline.getId())
                || !Objects.equals(plan.getOutlineRevision(), outline.getRevision())) {
            return null;
        }
        List<ScenePlanContent> scenes = planScenes(plan.getId());
        return new PlanningContext(outline.getId(), outline.getRevision(), outline.getVersion(),
                plan.getId(), plan.getVersion(), summarizeScenes(scenes), scenes);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public PlanningChangePackageView createCandidate(
            Long assistanceId,
            ModelPlanningProposal proposal) {
        ChapterSelectionAssistanceEntity assistance = requireAssistance(assistanceId);
        PlanningContext frozenContext = planningContext(assistance);
        validateModelProposal(assistance, proposal, frozenContext);
        List<ScenePlanContent> scenes = planningCodec.scenes(proposal.scenes());
        String normalizedKey = MODEL_PACKAGE_KEY_PREFIX + assistanceId;
        String scenesJson = json(scenes);
        String changeReason = proposal.changeReason().trim();
        String afterSummary = proposal.afterSummary().trim();
        ProsePlanningChangePackageEntity assistancePackage = findByAssistance(assistanceId);
        if (assistancePackage != null) {
            return reuseAssistancePackage(assistancePackage, normalizedKey, scenes,
                    changeReason, afterSummary);
        }
        ProsePlanningChangePackageEntity existing = findByIdempotency(assistance.getChapterId(), normalizedKey);
        if (existing != null) {
            if (!Objects.equals(existing.getAssistanceId(), assistanceId)
                    || !sameScenes(existing, scenes)
                    || !Objects.equals(existing.getChangeSummary(), changeReason)
                    || !Objects.equals(existing.getAfterSummary(), afterSummary)) {
                throw conflict("规划变更包幂等键已绑定不同输入");
            }
            return view(existing);
        }
        ChapterProseCandidateEntity candidate = candidateMapper.selectByIdForUpdate(
                assistance.getChapterId(), targetCandidateId(assistance));
        assistance = requireLockedAssistance(assistanceId);
        frozenContext = planningContext(assistance);
        validateModelProposal(assistance, proposal, frozenContext);
        requireFrozenCandidate(assistance, candidate);
        ChapterOutlineEntity outline = outlineMapper.findLatestForUpdate(assistance.getChapterId());
        ChapterPlanVersionEntity plan = planMapper.selectCurrentForUpdate(assistance.getChapterId());
        requireFrozenSources(frozenContext, outline, plan);
        assistancePackage = findByAssistance(assistanceId);
        if (assistancePackage != null) {
            return reuseAssistancePackage(assistancePackage, normalizedKey, scenes,
                    changeReason, afterSummary);
        }
        existing = findByIdempotency(assistance.getChapterId(), normalizedKey);
        if (existing != null) {
            if (!Objects.equals(existing.getAssistanceId(), assistanceId)
                    || !sameScenes(existing, scenes)
                    || !Objects.equals(existing.getChangeSummary(), changeReason)
                    || !Objects.equals(existing.getAfterSummary(), afterSummary)) {
                throw conflict("规划变更包幂等键已绑定不同输入");
            }
            return view(existing);
        }
        ProsePlanningChangePackageEntity entity = new ProsePlanningChangePackageEntity();
        entity.setWorkId(assistance.getWorkId());
        entity.setChapterId(assistance.getChapterId());
        entity.setAssistanceId(assistanceId);
        entity.setTargetCandidateId(targetCandidateId(assistance));
        entity.setIdempotencyKey(normalizedKey);
        entity.setPackageStatus(PACKAGE_CANDIDATE);
        entity.setChangeSummary(changeReason);
        entity.setBeforeSummary(frozenContext.beforeSummary());
        entity.setAfterSummary(afterSummary);
        entity.setTargetCandidateVersion(candidate.getVersion());
        entity.setTargetCandidateHash(candidate.getContentHash());
        entity.setBaseOutlineId(outline.getId());
        entity.setBaseOutlineRevision(outline.getRevision());
        entity.setBaseOutlineVersion(outline.getVersion());
        entity.setBaseScenePlanId(plan.getId());
        entity.setBaseScenePlanVersion(plan.getVersion());
        entity.setProposedScenesJson(scenesJson);
        entity.setDeleted(0);
        entity.setVersion(0);
        packageMapper.insert(entity);
        return view(entity);
    }

    @Override
    public PlanningChangePackageView getByAssistance(Long assistanceId) {
        requireAssistance(assistanceId);
        ProsePlanningChangePackageEntity entity = packageMapper.selectOne(
                new LambdaQueryWrapper<ProsePlanningChangePackageEntity>()
                        .eq(ProsePlanningChangePackageEntity::getAssistanceId, assistanceId)
                        .eq(ProsePlanningChangePackageEntity::getDeleted, 0));
        return entity == null ? null : view(entity);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public Long apply(
            Long chapterId,
            ChapterProseCandidateEntity candidate,
            Long packageId,
            Integer savedCandidateVersion,
            String savedCandidateHash,
            List<Long> appliedProposalIds) {
        ProsePlanningChangePackageEntity changePackage = requireLockedPackage(chapterId, packageId);
        requireSourceProposalIncluded(changePackage, appliedProposalIds);
        if (PACKAGE_APPLIED.equals(changePackage.getPackageStatus())) {
            requireApplied(changePackage, candidate.getId(), savedCandidateVersion, savedCandidateHash);
            return changePackage.getResultScenePlanId();
        }
        if (!PACKAGE_CANDIDATE.equals(changePackage.getPackageStatus())
                || !Objects.equals(changePackage.getTargetCandidateId(), candidate.getId())) {
            throw conflict("规划变更包不属于当前正文候选或状态不可应用");
        }
        if (!Objects.equals(changePackage.getTargetCandidateVersion(), candidate.getVersion())
                || !Objects.equals(changePackage.getTargetCandidateHash(), candidate.getContentHash())) {
            throw conflict("正文候选已在规划确认后变化，请重新生成并确认规划变更包");
        }
        ChapterOutlineEntity outline = outlineMapper.findLatestForUpdate(chapterId);
        ChapterPlanVersionEntity currentPlan = planMapper.selectCurrentForUpdate(chapterId);
        requireCurrentSources(changePackage, outline, currentPlan);
        if (planMapper.supersedeCurrentIfVersion(currentPlan.getId(), currentPlan.getVersion()) != 1) {
            throw conflict("权威场景规划版本已变化");
        }
        ChapterPlanVersionEntity resultPlan = createResultPlan(changePackage, currentPlan);
        planMapper.insert(resultPlan);
        insertScenes(resultPlan.getId(), scenes(changePackage.getProposedScenesJson()));
        if (packageMapper.markApplied(changePackage.getId(), savedCandidateVersion, savedCandidateHash,
                resultPlan.getId(), changePackage.getVersion()) != 1) {
            throw conflict("规划变更包状态已变化");
        }
        return resultPlan.getId();
    }

    @Override
    public void requireApplied(
            Long chapterId,
            Long candidateId,
            Long packageId,
            Integer savedCandidateVersion,
            String savedCandidateHash,
            List<Long> appliedProposalIds) {
        ProsePlanningChangePackageEntity changePackage = requireLockedPackage(chapterId, packageId);
        requireSourceProposalIncluded(changePackage, appliedProposalIds);
        requireApplied(changePackage, candidateId, savedCandidateVersion, savedCandidateHash);
    }

    private void requireSourceProposalIncluded(
            ProsePlanningChangePackageEntity changePackage,
            List<Long> appliedProposalIds) {
        if (appliedProposalIds == null || !appliedProposalIds.contains(changePackage.getAssistanceId())) {
            throw conflict("规划联动保存必须同时结算生成该规划包的正文修改提案");
        }
    }

    private void requireApplied(
            ProsePlanningChangePackageEntity changePackage,
            Long candidateId,
            Integer savedCandidateVersion,
            String savedCandidateHash) {
        if (!PACKAGE_APPLIED.equals(changePackage.getPackageStatus())
                || !Objects.equals(changePackage.getTargetCandidateId(), candidateId)
                || !Objects.equals(changePackage.getAppliedCandidateVersion(), savedCandidateVersion)
                || !Objects.equals(changePackage.getAppliedCandidateHash(), savedCandidateHash)
                || changePackage.getResultScenePlanId() == null) {
            throw conflict("重复保存与已应用的规划变更结果不一致");
        }
    }

    private ChapterPlanVersionEntity createResultPlan(
            ProsePlanningChangePackageEntity changePackage,
            ChapterPlanVersionEntity source) {
        ChapterPlanVersionEntity result = new ChapterPlanVersionEntity();
        result.setWorkId(source.getWorkId());
        result.setChapterId(source.getChapterId());
        result.setPlanNo(planMapper.selectMaxPlanNo(source.getChapterId()) + 1);
        result.setNarrativePlanId(source.getNarrativePlanId());
        result.setNarrativePlanNo(source.getNarrativePlanNo());
        result.setOutlineId(source.getOutlineId());
        result.setOutlineRevision(source.getOutlineRevision());
        result.setOutlineContentSchemaVersion(source.getOutlineContentSchemaVersion());
        result.setOutlineMigrationReviewStatus(source.getOutlineMigrationReviewStatus());
        result.setAgentRunId(null);
        result.setAiTaskId(source.getAiTaskId());
        result.setPlanStatus("published");
        result.setContentJson(source.getContentJson());
        result.setSourceType("prose_planning_change");
        result.setSourceScenePlanId(source.getId());
        result.setSourceScenePlanVersion(source.getVersion());
        result.setRevisionIdempotencyKey("prose-planning:" + changePackage.getId());
        result.setCreatedBy(LOCAL_USER);
        result.setPublishedBy(LOCAL_USER);
        result.setCurrentMarker(1);
        result.setSourceSnapshotId(source.getSourceSnapshotId());
        result.setValidityStatus("current");
        result.setValidityReasonCodesJson(null);
        result.setPublishedConsistencyReportId(null);
        result.setDeleted(0);
        result.setVersion(0);
        return result;
    }

    private void insertScenes(Long planId, List<ScenePlanContent> scenes) {
        for (ScenePlanContent scene : scenes) {
            ScenePlanVersionEntity entity = new ScenePlanVersionEntity();
            entity.setChapterPlanVersionId(planId);
            entity.setSceneKey(scene.sceneKey());
            entity.setSequenceNo(scene.sequence());
            entity.setContentSchemaVersion(PlanningContentCodec.CURRENT_SCENE_CONTENT_SCHEMA_VERSION);
            entity.setContentJson(json(scene));
            entity.setDeleted(0);
            entity.setVersion(0);
            sceneMapper.insert(entity);
        }
    }

    private void requireCurrentSources(
            ProsePlanningChangePackageEntity changePackage,
            ChapterOutlineEntity outline,
            ChapterPlanVersionEntity currentPlan) {
        if (outline == null || currentPlan == null
                || !Objects.equals(changePackage.getBaseOutlineId(), outline.getId())
                || !Objects.equals(changePackage.getBaseOutlineRevision(), outline.getRevision())
                || !Objects.equals(changePackage.getBaseOutlineVersion(), outline.getVersion())
                || !Objects.equals(changePackage.getBaseScenePlanId(), currentPlan.getId())
                || !Objects.equals(changePackage.getBaseScenePlanVersion(), currentPlan.getVersion())) {
            throw conflict("规划变更包来源已过期，请重新确认修改");
        }
    }

    private void validateModelProposal(
            ChapterSelectionAssistanceEntity assistance,
            ModelPlanningProposal proposal,
            PlanningContext frozenContext) {
        if (proposal == null || !StringUtils.hasText(proposal.changeReason())
                || proposal.changeReason().trim().length() > 500
                || !StringUtils.hasText(proposal.beforeSummary())
                || proposal.beforeSummary().trim().length() > MAX_SUMMARY_LENGTH
                || !StringUtils.hasText(proposal.afterSummary())
                || proposal.afterSummary().trim().length() > MAX_SUMMARY_LENGTH
                || frozenContext == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "模型规划提案的原因、摘要和场景必须符合结构化契约");
        }
        if (OPERATION_DISCUSS.equals(assistance.getOperationType())
                || !PACKAGE_SOURCE_STATUSES.contains(assistance.getRequestStatus())) {
            throw conflict("只有已完成的正文修改提案可以创建规划变更包");
        }
        if (!Objects.equals(frozenContext.beforeSummary(), proposal.beforeSummary().trim())) {
            throw conflict("模型规划提案的变更前摘要与冻结来源不一致");
        }
        targetCandidateId(assistance);
    }

    private PlanningContext planningContext(ChapterSelectionAssistanceEntity assistance) {
        if (!StringUtils.hasText(assistance.getPlanningContextJson())) {
            return null;
        }
        try {
            return objectMapper.readValue(assistance.getPlanningContextJson(), PlanningContext.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "正文协助的规划来源快照无法读取", exception);
        }
    }

    private void requireFrozenSources(
            PlanningContext frozenContext,
            ChapterOutlineEntity outline,
            ChapterPlanVersionEntity plan) {
        if (outline == null || plan == null
                || !Objects.equals(frozenContext.baseOutlineId(), outline.getId())
                || !Objects.equals(frozenContext.baseOutlineRevision(), outline.getRevision())
                || !Objects.equals(frozenContext.baseOutlineVersion(), outline.getVersion())
                || !Objects.equals(frozenContext.baseScenePlanId(), plan.getId())
                || !Objects.equals(frozenContext.baseScenePlanVersion(), plan.getVersion())) {
            throw conflict("模型规划提案来源已过期，请重新发起正文修改");
        }
    }

    private Long targetCandidateId(ChapterSelectionAssistanceEntity assistance) {
        Long candidateId = assistance.getCreatedCandidateId() == null
                ? assistance.getTargetCandidateId() : assistance.getCreatedCandidateId();
        if (candidateId == null) {
            throw conflict("正文修改提案尚未绑定稳定候选");
        }
        return candidateId;
    }

    private ChapterSelectionAssistanceEntity requireLockedAssistance(Long assistanceId) {
        ChapterSelectionAssistanceEntity entity = assistanceMapper.selectByIdForUpdate(assistanceId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "正文修改提案不存在");
        }
        return entity;
    }

    private void requireFrozenCandidate(
            ChapterSelectionAssistanceEntity assistance,
            ChapterProseCandidateEntity candidate) {
        Long candidateId = targetCandidateId(assistance);
        if (candidate == null
                || !Objects.equals(candidate.getId(), candidateId)
                || !Objects.equals(candidate.getVersion(), assistance.getTargetContentVersion())
                || !Objects.equals(candidate.getContentHash(), assistance.getTargetContentHash())) {
            throw conflict("正文候选版本或哈希已变化，不能创建规划变更包");
        }
    }

    private ChapterSelectionAssistanceEntity requireAssistance(Long assistanceId) {
        ChapterSelectionAssistanceEntity entity = assistanceId == null
                ? null : assistanceMapper.selectById(assistanceId);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "正文修改提案不存在");
        }
        return entity;
    }

    private ProsePlanningChangePackageEntity findByIdempotency(Long chapterId, String idempotencyKey) {
        return packageMapper.selectOne(new LambdaQueryWrapper<ProsePlanningChangePackageEntity>()
                .eq(ProsePlanningChangePackageEntity::getChapterId, chapterId)
                .eq(ProsePlanningChangePackageEntity::getIdempotencyKey, idempotencyKey)
                .eq(ProsePlanningChangePackageEntity::getDeleted, 0));
    }

    private ProsePlanningChangePackageEntity findByAssistance(Long assistanceId) {
        return packageMapper.selectOne(new LambdaQueryWrapper<ProsePlanningChangePackageEntity>()
                .eq(ProsePlanningChangePackageEntity::getAssistanceId, assistanceId)
                .eq(ProsePlanningChangePackageEntity::getDeleted, 0));
    }

    private ProsePlanningChangePackageEntity requireLockedPackage(Long chapterId, Long packageId) {
        ProsePlanningChangePackageEntity entity = packageId == null
                ? null : packageMapper.selectByIdForUpdate(chapterId, packageId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "规划变更包不存在");
        }
        return entity;
    }

    private PlanningChangePackageView view(ProsePlanningChangePackageEntity entity) {
        return new PlanningChangePackageView(
                entity.getId(), "candidate:" + entity.getTargetCandidateId(), entity.getTargetCandidateVersion(),
                entity.getPackageStatus(), entity.getChangeSummary(), entity.getBeforeSummary(), entity.getAfterSummary(),
                entity.getBaseOutlineRevision(), entity.getBaseOutlineVersion(),
                entity.getBaseScenePlanVersion(), scenes(entity.getProposedScenesJson()),
                entity.getAppliedCandidateVersion(),
                entity.getVersion(), entity.getGmtCreate(), entity.getGmtModified());
    }

    private PlanningChangePackageView reuseAssistancePackage(
            ProsePlanningChangePackageEntity changePackage,
            String idempotencyKey,
            List<ScenePlanContent> requestedScenes,
            String changeSummary,
            String afterSummary) {
        if (!Objects.equals(changePackage.getIdempotencyKey(), idempotencyKey)
                || !sameScenes(changePackage, requestedScenes)
                || !Objects.equals(changePackage.getChangeSummary(), changeSummary)
                || !Objects.equals(changePackage.getAfterSummary(), afterSummary)) {
            throw conflict("当前正文修改提案已经绑定不同的规划变更包");
        }
        return view(changePackage);
    }

    private boolean sameScenes(
            ProsePlanningChangePackageEntity changePackage,
            List<ScenePlanContent> requestedScenes) {
        return Objects.equals(scenes(changePackage.getProposedScenesJson()), requestedScenes);
    }

    private List<ScenePlanContent> planScenes(Long planId) {
        return sceneMapper.findAllByPlanId(planId).stream()
                .filter(entity -> !Integer.valueOf(1).equals(entity.getDeleted()))
                .map(entity -> readScene(entity.getContentJson()))
                .toList();
    }

    private ScenePlanContent readScene(String value) {
        try {
            return objectMapper.readValue(value, ScenePlanContent.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "权威场景规划无法读取", exception);
        }
    }

    private String summarizeScenes(List<ScenePlanContent> scenes) {
        if (scenes.isEmpty()) {
            return "共 0 场";
        }
        String details = scenes.stream()
                .sorted((left, right) -> Integer.compare(left.sequence(), right.sequence()))
                .map(scene -> scene.sequence() + ". " + scene.title() + "：" + scene.expectedOutcome())
                .collect(Collectors.joining("；"));
        String summary = "共 " + scenes.size() + " 场；" + details;
        return summary.substring(0, Math.min(MAX_SUMMARY_LENGTH, summary.length()));
    }

    private List<ScenePlanContent> scenes(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "规划变更包无法读取", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "规划变更包无法序列化", exception);
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.SCENE_PLAN_CONFLICT, message);
    }
}
