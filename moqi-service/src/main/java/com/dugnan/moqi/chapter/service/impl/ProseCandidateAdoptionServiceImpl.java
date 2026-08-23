package com.dugnan.moqi.chapter.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.AdoptProseCandidateRequest;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.AdoptionReadiness;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseCandidateAdoptionView;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.entity.ProseCandidateAdoptionEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import com.dugnan.moqi.chapter.mapper.ProseCandidateAdoptionMapper;
import com.dugnan.moqi.chapter.mapper.ProsePlanningChangePackageMapper;
import com.dugnan.moqi.chapter.service.ProseCandidateAdoptionService;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.impact.ProseImpactModels.CreateReportRequest;
import com.dugnan.moqi.impact.ProseImpactModels.CreateReportResult;
import com.dugnan.moqi.impact.ProseImpactService;
import com.dugnan.moqi.release.StoryReleaseModels.CandidateAdoptionDraft;
import com.dugnan.moqi.release.StoryReleaseModels.CandidateAdoptionDraftRequest;
import com.dugnan.moqi.release.StoryReleaseService;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-08-23
 * @description 以候选、评价、待确认项和正式正文的固定锁序实现幂等采纳。
 */
@Service
public class ProseCandidateAdoptionServiceImpl implements ProseCandidateAdoptionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProseCandidateAdoptionServiceImpl.class);
    private static final String MODE_DIRECT = "direct_formal";
    private static final String MODE_RELEASE = "revision_release";
    private static final String CANDIDATE_ACTIVE = "active";
    private static final String ADOPTION_UNADOPTED = "unadopted";
    private static final String REPORT_READY = "ready";
    private static final String REPORT_QUEUED = "queued";
    private static final String REPORT_RUNNING = "running";
    private static final String BLOCK_CANDIDATE_NOT_ACTIVE = "candidate_not_active";
    private static final String BLOCK_CANDIDATE_ALREADY_ADOPTED = "candidate_already_adopted";
    private static final String BLOCK_QUALITY_PREFIX = "quality_";
    private static final String BLOCK_PENDING_PROPOSALS = "pending_proposals";
    private static final String BLOCK_PENDING_PLANNING = "pending_planning_change";
    private static final String ACTION_RESOLVE_QUALITY = "resolve_quality_gate";
    private static final String ACTION_RESOLVE_PROPOSALS = "resolve_pending_proposals";
    private static final String ACTION_RESOLVE_PLANNING = "resolve_planning_change";
    private static final String ACTION_VIEW_RESULT = "view_adoption_result";
    private static final List<String> ADOPTABLE_CONCLUSIONS = List.of("pass", "warning");
    private static final int RECOVERY_BATCH_SIZE = 100;

    private final ChapterProseCandidateMapper candidateMapper;
    private final ChapterGenerationEvaluationReportMapper reportMapper;
    private final ChapterSelectionAssistanceMapper assistanceMapper;
    private final ProsePlanningChangePackageMapper planningPackageMapper;
    private final ProseCandidateAdoptionMapper adoptionMapper;
    private final ChapterMapper chapterMapper;
    private final WorkMapper workMapper;
    private final StoryReleaseService storyReleaseService;
    private final ProseImpactService impactService;
    private final GenerationEvaluationService evaluationService;

    public ProseCandidateAdoptionServiceImpl(
            ChapterProseCandidateMapper candidateMapper,
            ChapterGenerationEvaluationReportMapper reportMapper,
            ChapterSelectionAssistanceMapper assistanceMapper,
            ProsePlanningChangePackageMapper planningPackageMapper,
            ProseCandidateAdoptionMapper adoptionMapper,
            ChapterMapper chapterMapper,
            WorkMapper workMapper,
            StoryReleaseService storyReleaseService,
            ProseImpactService impactService,
            GenerationEvaluationService evaluationService) {
        this.candidateMapper = candidateMapper;
        this.reportMapper = reportMapper;
        this.assistanceMapper = assistanceMapper;
        this.planningPackageMapper = planningPackageMapper;
        this.adoptionMapper = adoptionMapper;
        this.chapterMapper = chapterMapper;
        this.workMapper = workMapper;
        this.storyReleaseService = storyReleaseService;
        this.impactService = impactService;
        this.evaluationService = evaluationService;
    }

    @Override
    public AdoptionReadiness readiness(ChapterProseCandidateEntity candidate) {
        ChapterEntity chapter = chapterMapper.selectById(candidate.getChapterId());
        String mode = chapter != null && chapter.getCurrentProseRevisionId() != null ? MODE_RELEASE : MODE_DIRECT;
        List<String> blocking = new ArrayList<>();
        ChapterGenerationEvaluationReportEntity report = latestWholeReport(candidate.getQualityGenerationId());
        if (!CANDIDATE_ACTIVE.equals(candidate.getCandidateStatus())) {
            blocking.add(BLOCK_CANDIDATE_NOT_ACTIVE);
        }
        if (!ADOPTION_UNADOPTED.equals(candidate.getAdoptionStatus())) {
            blocking.add(BLOCK_CANDIDATE_ALREADY_ADOPTED);
        }
        validateReportForReadiness(candidate, report, blocking);
        if (blocking.isEmpty()) {
            try {
                evaluationService.requireAdoptable(candidate.getChapterId(), candidate.getQualityGenerationId());
            } catch (BusinessException exception) {
                blocking.add("quality_source_not_adoptable");
            }
        }
        if (pendingProposalCount(candidate) > 0) {
            blocking.add(BLOCK_PENDING_PROPOSALS);
        }
        if (pendingPlanningCount(candidate) > 0) {
            blocking.add(BLOCK_PENDING_PLANNING);
        }
        return new AdoptionReadiness(blocking.isEmpty(), mode, report == null ? null : report.getId(),
                List.copyOf(blocking), nextActions(blocking));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ProseCandidateAdoptionView adopt(
            Long chapterId,
            Long candidateId,
            AdoptProseCandidateRequest request) {
        requireRequest(request);
        ChapterProseCandidateEntity candidateSnapshot = candidateMapper.selectById(candidateId);
        if (candidateSnapshot == null || Integer.valueOf(1).equals(candidateSnapshot.getDeleted())
                || !Objects.equals(candidateSnapshot.getChapterId(), chapterId)) {
            throw new BusinessException(ErrorCode.PROSE_CANDIDATE_NOT_FOUND, "正文候选不存在");
        }
        WorkEntity work = workMapper.selectByIdForUpdate(candidateSnapshot.getWorkId());
        ChapterEntity chapter = chapterMapper.selectByIdForUpdate(chapterId);
        if (work == null || chapter == null || Integer.valueOf(1).equals(work.getDeleted())
                || Integer.valueOf(1).equals(chapter.getDeleted()) || !Objects.equals(work.getId(), chapter.getWorkId())
                || !Objects.equals(work.getId(), candidateSnapshot.getWorkId())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        ChapterProseCandidateEntity candidate = candidateMapper.selectByIdForUpdate(chapterId, candidateId);
        requireCandidateInput(candidate, request);
        ProseCandidateAdoptionEntity replay = replayOrConflict(candidate, request);
        if (replay != null) {
            if (MODE_RELEASE.equals(replay.getAdoptionMode())) {
                scheduleImpactAfterCommit(replay);
            }
            return view(replay);
        }
        if (!CANDIDATE_ACTIVE.equals(candidate.getCandidateStatus())
                || !ADOPTION_UNADOPTED.equals(candidate.getAdoptionStatus())) {
            throw conflict("候选状态不允许采纳");
        }
        ChapterGenerationEvaluationReportEntity report = reportMapper.selectByIdForUpdate(
                chapterId, request.qualityReportId());
        List<ChapterGenerationEvaluationReportEntity> wholeReports =
                reportMapper.selectWholeReportsForUpdate(candidate.getQualityGenerationId());
        requireAdoptableReport(candidate, report, wholeReports.isEmpty() ? null : wholeReports.get(0));
        if (!assistanceMapper.selectPendingForAdoption(chapterId, candidateId).isEmpty()) {
            throw blocked("候选仍有待确认正文修改提案");
        }
        if (!planningPackageMapper.selectPendingForAdoption(chapterId, candidateId).isEmpty()) {
            throw blocked("候选仍有待确认规划变更");
        }
        return chapter.getCurrentProseRevisionId() == null
                ? adoptDirect(candidate, report, request, chapter)
                : adoptPublished(candidate, report, request, chapter);
    }

    private ProseCandidateAdoptionView adoptDirect(
            ChapterProseCandidateEntity candidate,
            ChapterGenerationEvaluationReportEntity report,
            AdoptProseCandidateRequest request,
            ChapterEntity chapter) {
        requireFormalVersion(chapter, request.expectedFormalVersion(), false);
        ProseCandidateAdoptionEntity adoption = newAdoption(candidate, report, request, MODE_DIRECT);
        adoption.setAdoptionStatus("processing");
        adoptionMapper.insert(adoption);
        if (chapterMapper.updateContentIfVersion(chapter.getId(), candidate.getContent(), request.expectedFormalVersion()) != 1) {
            throw conflict("正式正文版本已变化");
        }
        markCandidate(candidate, "adopted");
        adoption.setAdoptionStatus("completed");
        adoption.setFormalResultVersion(request.expectedFormalVersion() + 1);
        adoption.setFormalResultHash(candidate.getContentHash());
        adoptionMapper.updateById(adoption);
        return view(adoption);
    }

    private ProseCandidateAdoptionView adoptPublished(
            ChapterProseCandidateEntity candidate,
            ChapterGenerationEvaluationReportEntity report,
            AdoptProseCandidateRequest request,
            ChapterEntity snapshot) {
        CandidateAdoptionDraft draft = storyReleaseService.ensureCandidateAdoptionDraft(
                candidate.getWorkId(), candidate.getChapterId(), new CandidateAdoptionDraftRequest(
                        snapshot.getCurrentProseRevisionId(), candidate.getQualityGenerationId(), candidate.getContent(),
                        report.getId(), request.expectedFormalVersion(), "candidate-adoption:" + request.idempotencyKey()));
        ProseCandidateAdoptionEntity adoption = newAdoption(candidate, report, request, MODE_RELEASE);
        adoption.setAdoptionStatus("impact_pending");
        adoption.setRevisionId(draft.revisionId());
        adoption.setWorkspaceId(draft.workspaceId());
        adoptionMapper.insert(adoption);
        markCandidate(candidate, "release_pending");
        scheduleImpactAfterCommit(adoption);
        return view(adoption);
    }

    @Override
    public void resumePendingImpacts() {
        adoptionMapper.selectPendingImpact(RECOVERY_BATCH_SIZE).forEach(this::startImpactSafely);
    }

    private void scheduleImpactAfterCommit(ProseCandidateAdoptionEntity adoption) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    startImpactSafely(adoption);
                }
            });
        } else {
            startImpactSafely(adoption);
        }
    }

    private void startImpactSafely(ProseCandidateAdoptionEntity adoption) {
        try {
            CreateReportResult result = impactService.create(adoption.getWorkId(), adoption.getChapterId(),
                    adoption.getRevisionId(), new CreateReportRequest(adoption.getWorkspaceId(), null,
                            "candidate-adoption-impact:" + adoption.getId()));
            adoptionMapper.bindImpactReport(adoption.getId(), result.report().id());
        } catch (RuntimeException exception) {
            adoptionMapper.markImpactStartFailed(adoption.getId(), "impact_start_unavailable");
            LOGGER.error("候选采纳影响分析启动失败，adoptionId={}", adoption.getId(), exception);
        }
    }

    private void requireRequest(AdoptProseCandidateRequest request) {
        if (request == null || request.candidateVersion() == null || !StringUtils.hasText(request.contentHash())
                || request.expectedFormalVersion() == null || request.qualityReportId() == null
                || !StringUtils.hasText(request.idempotencyKey()) || !Boolean.TRUE.equals(request.userConfirmed())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "采纳必须提交候选版本、内容哈希、正式正文版本、质量报告、幂等键和用户确认");
        }
    }

    private void requireCandidateInput(
            ChapterProseCandidateEntity candidate,
            AdoptProseCandidateRequest request) {
        if (candidate == null) {
            throw new BusinessException(ErrorCode.PROSE_CANDIDATE_NOT_FOUND, "正文候选不存在");
        }
        if (!Objects.equals(candidate.getVersion(), request.candidateVersion())
                || !Objects.equals(candidate.getContentHash(), request.contentHash())
                || !Objects.equals(hash(candidate.getContent()), candidate.getContentHash())) {
            throw conflict("候选版本或正文哈希已变化");
        }
    }

    private void requireAdoptableReport(
            ChapterProseCandidateEntity candidate,
            ChapterGenerationEvaluationReportEntity report,
            ChapterGenerationEvaluationReportEntity latest) {
        if (report == null || latest == null || !Objects.equals(report.getId(), latest.getId())
                || report.getGenerationSceneId() != null
                || !Objects.equals(report.getGenerationId(), candidate.getQualityGenerationId())
                || !Objects.equals(report.getContentHash(), candidate.getContentHash())
                || !REPORT_READY.equals(report.getReportStatus())
                || !ADOPTABLE_CONCLUSIONS.contains(report.getConclusion())) {
            throw blocked("质量报告不是当前候选正文对应的最新可采纳整章报告");
        }
        evaluationService.requireAdoptable(candidate.getChapterId(), candidate.getQualityGenerationId());
    }

    private void requireFormalVersion(ChapterEntity chapter, Integer expectedVersion, boolean published) {
        if (chapter == null || !Objects.equals(chapter.getVersion(), expectedVersion)
                || (chapter.getCurrentProseRevisionId() != null) != published) {
            throw conflict("正式正文版本或发布状态已变化");
        }
    }

    private ProseCandidateAdoptionEntity replayOrConflict(
            ChapterProseCandidateEntity candidate,
            AdoptProseCandidateRequest request) {
        ProseCandidateAdoptionEntity replay = adoptionMapper.selectReplayForUpdate(
                candidate.getChapterId(), request.idempotencyKey());
        if (replay == null) {
            replay = adoptionMapper.selectOne(new LambdaQueryWrapper<ProseCandidateAdoptionEntity>()
                    .eq(ProseCandidateAdoptionEntity::getCandidateId, candidate.getId())
                    .eq(ProseCandidateAdoptionEntity::getCandidateVersion, candidate.getVersion())
                    .eq(ProseCandidateAdoptionEntity::getDeleted, 0));
        }
        if (replay == null) {
            return null;
        }
        if (!Objects.equals(replay.getCandidateId(), candidate.getId())
                || !Objects.equals(replay.getCandidateVersion(), request.candidateVersion())
                || !Objects.equals(replay.getCandidateContentHash(), request.contentHash())
                || !Objects.equals(replay.getExpectedFormalVersion(), request.expectedFormalVersion())
                || !Objects.equals(replay.getQualityReportId(), request.qualityReportId())
                || !Objects.equals(replay.getIdempotencyKey(), request.idempotencyKey())) {
            throw conflict("采纳幂等键或候选版本已经用于其他输入");
        }
        return replay;
    }

    private ProseCandidateAdoptionEntity newAdoption(
            ChapterProseCandidateEntity candidate,
            ChapterGenerationEvaluationReportEntity report,
            AdoptProseCandidateRequest request,
            String mode) {
        ProseCandidateAdoptionEntity adoption = new ProseCandidateAdoptionEntity();
        adoption.setWorkId(candidate.getWorkId());
        adoption.setChapterId(candidate.getChapterId());
        adoption.setCandidateId(candidate.getId());
        adoption.setCandidateVersion(candidate.getVersion());
        adoption.setCandidateContentHash(candidate.getContentHash());
        adoption.setExpectedFormalVersion(request.expectedFormalVersion());
        adoption.setQualityReportId(report.getId());
        adoption.setIdempotencyKey(request.idempotencyKey());
        adoption.setAdoptionMode(mode);
        adoption.setDeleted(0);
        adoption.setVersion(0);
        return adoption;
    }

    private void markCandidate(ChapterProseCandidateEntity candidate, String status) {
        if (candidateMapper.markAdoptionStatus(candidate.getChapterId(), candidate.getId(), candidate.getVersion(),
                candidate.getContentHash(), status) != 1) {
            throw conflict("候选采纳状态发生并发变化");
        }
    }

    private ChapterGenerationEvaluationReportEntity latestWholeReport(Long generationId) {
        if (generationId == null) {
            return null;
        }
        return reportMapper.selectOne(new LambdaQueryWrapper<ChapterGenerationEvaluationReportEntity>()
                .eq(ChapterGenerationEvaluationReportEntity::getGenerationId, generationId)
                .isNull(ChapterGenerationEvaluationReportEntity::getGenerationSceneId)
                .eq(ChapterGenerationEvaluationReportEntity::getDeleted, 0)
                .orderByDesc(ChapterGenerationEvaluationReportEntity::getId)
                .last("LIMIT 1"));
    }

    private void validateReportForReadiness(
            ChapterProseCandidateEntity candidate,
            ChapterGenerationEvaluationReportEntity report,
            List<String> blocking) {
        if (report == null) {
            blocking.add("quality_report_missing");
        } else if (!Objects.equals(report.getContentHash(), candidate.getContentHash())) {
            blocking.add("quality_report_stale");
        } else if (List.of(REPORT_QUEUED, REPORT_RUNNING).contains(report.getReportStatus())) {
            blocking.add("quality_evaluating");
        } else if (!REPORT_READY.equals(report.getReportStatus())) {
            blocking.add("quality_unavailable");
        } else if (!ADOPTABLE_CONCLUSIONS.contains(report.getConclusion())) {
            blocking.add("quality_not_adoptable");
        }
    }

    private long pendingProposalCount(ChapterProseCandidateEntity candidate) {
        return assistanceMapper.selectCount(new LambdaQueryWrapper<com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity>()
                .eq(com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity::getChapterId, candidate.getChapterId())
                .and(wrapper -> wrapper.eq(com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity::getTargetCandidateId,
                                candidate.getId())
                        .or().eq(com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity::getCreatedCandidateId,
                                candidate.getId()))
                .in(com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity::getRequestStatus,
                        "ready", "review_required")
                .eq(com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity::getProposalStatus, "ready")
                .eq(com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity::getDeleted, 0));
    }

    private long pendingPlanningCount(ChapterProseCandidateEntity candidate) {
        return planningPackageMapper.selectCount(
                new LambdaQueryWrapper<com.dugnan.moqi.chapter.entity.ProsePlanningChangePackageEntity>()
                        .eq(com.dugnan.moqi.chapter.entity.ProsePlanningChangePackageEntity::getChapterId,
                                candidate.getChapterId())
                        .eq(com.dugnan.moqi.chapter.entity.ProsePlanningChangePackageEntity::getTargetCandidateId,
                                candidate.getId())
                        .eq(com.dugnan.moqi.chapter.entity.ProsePlanningChangePackageEntity::getPackageStatus,
                                "candidate")
                        .eq(com.dugnan.moqi.chapter.entity.ProsePlanningChangePackageEntity::getDeleted, 0));
    }

    private List<String> nextActions(List<String> blocking) {
        List<String> actions = new ArrayList<>();
        if (blocking.stream().anyMatch(code -> code.startsWith(BLOCK_QUALITY_PREFIX))) {
            actions.add(ACTION_RESOLVE_QUALITY);
        }
        if (blocking.contains(BLOCK_PENDING_PROPOSALS)) {
            actions.add(ACTION_RESOLVE_PROPOSALS);
        }
        if (blocking.contains(BLOCK_PENDING_PLANNING)) {
            actions.add(ACTION_RESOLVE_PLANNING);
        }
        if (blocking.contains(BLOCK_CANDIDATE_ALREADY_ADOPTED)) {
            actions.add(ACTION_VIEW_RESULT);
        }
        return List.copyOf(actions);
    }

    private ProseCandidateAdoptionView view(ProseCandidateAdoptionEntity adoption) {
        return new ProseCandidateAdoptionView(adoption.getId(), adoption.getChapterId(), adoption.getCandidateId(),
                adoption.getCandidateVersion(), adoption.getCandidateContentHash(), adoption.getAdoptionMode(),
                adoption.getAdoptionStatus(), adoption.getFormalResultVersion(), adoption.getRevisionId(),
                adoption.getWorkspaceId(), adoption.getImpactReportId(), adoption.getGmtModified());
    }

    private BusinessException blocked(String message) {
        return new BusinessException(ErrorCode.PROSE_ADOPTION_BLOCKED, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.PROSE_ADOPTION_CONFLICT, message);
    }

    private String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNullElse(content, "").getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
