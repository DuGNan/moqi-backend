package com.dugnan.moqi.release;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.entity.BoundedChapterRevisionEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEvaluationReportEntity;
import com.dugnan.moqi.chapter.mapper.BoundedChapterRevisionMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationEvaluationReportMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.release.StoryReleaseModels.AbandonRevisionRequest;
import com.dugnan.moqi.release.StoryReleaseModels.AbandonWorkspaceRequest;
import com.dugnan.moqi.release.StoryReleaseModels.BindEvaluationRequest;
import com.dugnan.moqi.release.StoryReleaseModels.CandidateAdoptionDraft;
import com.dugnan.moqi.release.StoryReleaseModels.CandidateAdoptionDraftRequest;
import com.dugnan.moqi.release.StoryReleaseModels.CreateRevisionRequest;
import com.dugnan.moqi.release.StoryReleaseModels.CreateWorkspaceRequest;
import com.dugnan.moqi.release.StoryReleaseModels.PrepareWorkspaceRequest;
import com.dugnan.moqi.release.StoryReleaseModels.PublishWorkspaceRequest;
import com.dugnan.moqi.release.StoryReleaseModels.PutWorkspaceChapterRequest;
import com.dugnan.moqi.release.StoryReleaseModels.ReleaseChapterView;
import com.dugnan.moqi.release.StoryReleaseModels.ReleaseDiff;
import com.dugnan.moqi.release.StoryReleaseModels.ReleaseDiffEntry;
import com.dugnan.moqi.release.StoryReleaseModels.ReleaseView;
import com.dugnan.moqi.release.StoryReleaseModels.RevisionDiff;
import com.dugnan.moqi.release.StoryReleaseModels.RevisionView;
import com.dugnan.moqi.release.StoryReleaseModels.RollbackReleaseRequest;
import com.dugnan.moqi.release.StoryReleaseModels.WorkspaceChapterView;
import com.dugnan.moqi.release.StoryReleaseModels.WorkspaceView;
import com.dugnan.moqi.release.entity.ChapterProseRevisionEntity;
import com.dugnan.moqi.release.entity.StoryReleaseChapterEntity;
import com.dugnan.moqi.release.entity.StoryReleaseEntity;
import com.dugnan.moqi.release.entity.WorkRevisionWorkspaceChapterEntity;
import com.dugnan.moqi.release.entity.WorkRevisionWorkspaceEntity;
import com.dugnan.moqi.release.mapper.ChapterProseRevisionMapper;
import com.dugnan.moqi.release.mapper.StoryReleaseChapterMapper;
import com.dugnan.moqi.release.mapper.StoryReleaseMapper;
import com.dugnan.moqi.release.mapper.WorkRevisionWorkspaceChapterMapper;
import com.dugnan.moqi.release.mapper.WorkRevisionWorkspaceMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;
import com.dugnan.moqi.impact.ProseImpactReleaseHook;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 以不可变正文 revision 和短事务实现 Story Release 原子发布与回退。
 */
@Service
public class StoryReleaseServiceImpl implements StoryReleaseService {
    private static final String LOCAL_USER = "local-user";
    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_REVIEWING = "reviewing";
    private static final String STATUS_CONFIRMABLE = "confirmable";
    private static final String STATUS_PUBLISHED = "published";
    private static final String STATUS_SUPERSEDED = "superseded";
    private static final String STATUS_ABANDONED = "abandoned";
    private static final String WORKSPACE_READY = "ready";
    private static final String WORKSPACE_PUBLISHED = "published";
    private static final String REPORT_QUEUED = "queued";
    private static final String REPORT_RUNNING = "running";
    private static final String REPORT_READY = "ready";
    private static final String ASSEMBLY_BOUNDED_REVISION = "bounded_revision";
    private static final String BOUNDED_RE_EVALUATING = "re_evaluating";
    private static final String BOUNDED_CANDIDATE_READY = "candidate_ready";
    private static final List<String> ADOPTABLE_CONCLUSIONS = List.of("pass", "warning");
    private static final int MAX_CONTENT_LENGTH = 1000000;

    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterGenerationMapper generationMapper;
    private final BoundedChapterRevisionMapper boundedRevisionMapper;
    private final ChapterGenerationEvaluationReportMapper evaluationReportMapper;
    private final ChapterProseRevisionMapper proseRevisionMapper;
    private final StoryReleaseMapper storyReleaseMapper;
    private final StoryReleaseChapterMapper releaseChapterMapper;
    private final WorkRevisionWorkspaceMapper workspaceMapper;
    private final WorkRevisionWorkspaceChapterMapper workspaceChapterMapper;
    private final GenerationEvaluationService evaluationService;
    private final ObjectMapper objectMapper;
    private ProseImpactReleaseHook proseImpactHook = ProseImpactReleaseHook.noop();

    public StoryReleaseServiceImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterGenerationMapper generationMapper,
            BoundedChapterRevisionMapper boundedRevisionMapper,
            ChapterGenerationEvaluationReportMapper evaluationReportMapper,
            ChapterProseRevisionMapper proseRevisionMapper,
            StoryReleaseMapper storyReleaseMapper,
            StoryReleaseChapterMapper releaseChapterMapper,
            WorkRevisionWorkspaceMapper workspaceMapper,
            WorkRevisionWorkspaceChapterMapper workspaceChapterMapper,
            GenerationEvaluationService evaluationService,
            ObjectMapper objectMapper) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.generationMapper = generationMapper;
        this.boundedRevisionMapper = boundedRevisionMapper;
        this.evaluationReportMapper = evaluationReportMapper;
        this.proseRevisionMapper = proseRevisionMapper;
        this.storyReleaseMapper = storyReleaseMapper;
        this.releaseChapterMapper = releaseChapterMapper;
        this.workspaceMapper = workspaceMapper;
        this.workspaceChapterMapper = workspaceChapterMapper;
        this.evaluationService = evaluationService;
        this.objectMapper = objectMapper;
    }

    @Autowired
    public void setProseImpactHook(ProseImpactReleaseHook proseImpactHook) {
        this.proseImpactHook = proseImpactHook;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public RevisionView createRevision(Long workId, Long chapterId, CreateRevisionRequest request) {
        requireWork(workId);
        ChapterEntity chapter = requireChapterForUpdate(workId, chapterId);
        requireCreateRevisionRequest(request);
        ChapterProseRevisionEntity existing = proseRevisionMapper.selectOne(
                new LambdaQueryWrapper<ChapterProseRevisionEntity>()
                        .eq(ChapterProseRevisionEntity::getChapterId, chapterId)
                        .eq(ChapterProseRevisionEntity::getIdempotencyKey, request.idempotencyKey())
                        .eq(ChapterProseRevisionEntity::getDeleted, 0));
        if (existing != null) {
            requireSameRevisionInput(existing, request);
            return revisionView(existing);
        }
        if (chapter.getCurrentProseRevisionId() != null
                && !Objects.equals(chapter.getCurrentProseRevisionId(), request.parentRevisionId())) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "已发布章节只能基于当前发布 revision 创建新草稿");
        }
        ChapterProseRevisionEntity parent = request.parentRevisionId() == null
                ? null : requireRevision(workId, chapterId, request.parentRevisionId());
        ChapterGenerationEntity generation = sourceGeneration(workId, chapterId, request.sourceGenerationId());
        BoundedChapterRevisionEntity bounded = sourceBoundedRevision(
                workId, chapterId, request.sourceBoundedRevisionId(), generation);
        String content = revisionContent(request.content(), generation);
        String contentHash = hash(content);
        ChapterProseRevisionEntity item = new ChapterProseRevisionEntity();
        item.setWorkId(workId);
        item.setChapterId(chapterId);
        item.setParentRevisionId(parent == null ? null : parent.getId());
        item.setSourceGenerationId(generation == null ? null : generation.getId());
        item.setSourceBoundedRevisionId(bounded == null ? null : bounded.getId());
        item.setSourceSnapshotId(generation != null ? generation.getSourceSnapshotId()
                : parent == null ? null : parent.getSourceSnapshotId());
        item.setRevisionNo(nextRevisionNo(chapterId));
        item.setRevisionOrigin(revisionOrigin(request, generation, bounded, content));
        item.setRevisionStatus(STATUS_DRAFT);
        item.setContent(content);
        item.setContentHash(contentHash);
        item.setIdempotencyKey(request.idempotencyKey());
        item.setCreatedBy(LOCAL_USER);
        item.setDeleted(0);
        item.setVersion(0);
        proseRevisionMapper.insert(item);
        return revisionView(item);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public CandidateAdoptionDraft ensureCandidateAdoptionDraft(
            Long workId,
            Long chapterId,
            CandidateAdoptionDraftRequest request) {
        if (request == null || request.parentRevisionId() == null || request.sourceGenerationId() == null
                || request.evaluationReportId() == null || request.expectedFormalVersion() == null
                || !StringUtils.hasText(request.idempotencyKey())) {
            throw badRequest("已发布正文采纳缺少 revision、评价、正式正文版本或幂等键");
        }
        WorkEntity work = requireWorkForUpdate(workId);
        ChapterEntity chapter = requireChapterForUpdate(workId, chapterId);
        if (!Objects.equals(chapter.getVersion(), request.expectedFormalVersion())
                || !Objects.equals(chapter.getCurrentProseRevisionId(), request.parentRevisionId())) {
            throw conflict(ErrorCode.CHAPTER_VERSION_CONFLICT, "正式正文版本或发布 revision 已变化");
        }
        RevisionView revision = createRevision(workId, chapterId, new CreateRevisionRequest(
                request.parentRevisionId(), request.sourceGenerationId(), null,
                request.content(), request.idempotencyKey() + ":revision"));
        if (!Objects.equals(revision.evaluationReportId(), request.evaluationReportId())) {
            revision = bindEvaluation(workId, chapterId, revision.id(),
                    new BindEvaluationRequest(request.evaluationReportId(), revision.version()));
        }
        WorkRevisionWorkspaceEntity active = workspaceMapper.selectOne(
                new LambdaQueryWrapper<WorkRevisionWorkspaceEntity>()
                        .eq(WorkRevisionWorkspaceEntity::getWorkId, workId)
                        .eq(WorkRevisionWorkspaceEntity::getCurrentMarker, 1)
                        .eq(WorkRevisionWorkspaceEntity::getDeleted, 0));
        WorkspaceView workspace;
        if (active == null) {
            workspace = createWorkspace(workId,
                    new CreateWorkspaceRequest(request.idempotencyKey() + ":workspace"));
        } else {
            if (!STATUS_DRAFT.equals(active.getWorkspaceStatus())
                    || !Objects.equals(active.getBaselineReleaseId(), work.getCurrentStoryReleaseId())
                    || !Objects.equals(active.getBaselineWorkVersion(), work.getVersion())) {
                throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "活动修订工作区不允许加入新的正文 revision");
            }
            workspace = workspaceView(active);
        }
        WorkspaceView updated = putWorkspaceChapter(workId, workspace.id(), chapterId,
                new PutWorkspaceChapterRequest(revision.id(), workspace.version()));
        return new CandidateAdoptionDraft(revision.id(), updated.id(), updated.version());
    }

    @Override
    public RevisionView revision(Long workId, Long chapterId, Long revisionId) {
        return revisionView(requireRevision(workId, chapterId, revisionId));
    }

    @Override
    public List<RevisionView> revisions(Long workId, Long chapterId) {
        requireChapter(workId, chapterId);
        return proseRevisionMapper.selectList(new LambdaQueryWrapper<ChapterProseRevisionEntity>()
                        .eq(ChapterProseRevisionEntity::getWorkId, workId)
                        .eq(ChapterProseRevisionEntity::getChapterId, chapterId)
                        .eq(ChapterProseRevisionEntity::getDeleted, 0)
                        .orderByAsc(ChapterProseRevisionEntity::getRevisionNo))
                .stream().map(this::revisionView).toList();
    }

    @Override
    public RevisionDiff compareRevisions(
            Long workId,
            Long chapterId,
            Long baseRevisionId,
            Long targetRevisionId) {
        ChapterProseRevisionEntity base = requireRevision(workId, chapterId, baseRevisionId);
        ChapterProseRevisionEntity target = requireRevision(workId, chapterId, targetRevisionId);
        return new RevisionDiff(base.getId(), target.getId(), base.getContentHash(), target.getContentHash(),
                base.getContent(), target.getContent(), !Objects.equals(base.getContentHash(), target.getContentHash()));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public RevisionView bindEvaluation(
            Long workId,
            Long chapterId,
            Long revisionId,
            BindEvaluationRequest request) {
        ChapterProseRevisionEntity revision = requireRevision(workId, chapterId, revisionId);
        if (request == null || request.evaluationReportId() == null || request.expectedVersion() == null) {
            throw badRequest("绑定评价必须提供 evaluationReportId 和 expectedVersion");
        }
        if (revision.getEvaluationReportId() != null
                && !Objects.equals(revision.getEvaluationReportId(), request.evaluationReportId())) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "revision 已冻结其他评价报告");
        }
        ChapterGenerationEvaluationReportEntity report = requireMatchingReport(revision, request.evaluationReportId());
        String nextStatus = evaluationBindingStatus(report);
        if (Objects.equals(revision.getEvaluationReportId(), report.getId())
                && Objects.equals(revision.getRevisionStatus(), nextStatus)) {
            return revisionView(revision);
        }
        if (!List.of(STATUS_DRAFT, STATUS_REVIEWING).contains(revision.getRevisionStatus())
                || !Objects.equals(revision.getVersion(), request.expectedVersion())) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "revision 状态或版本已变化");
        }
        if (STATUS_CONFIRMABLE.equals(nextStatus)) {
            evaluationService.requireAdoptable(chapterId, report.getGenerationId());
        }
        int updated = proseRevisionMapper.update(null, new UpdateWrapper<ChapterProseRevisionEntity>()
                .eq("id", revisionId).eq("version", request.expectedVersion())
                .in("revision_status", STATUS_DRAFT, STATUS_REVIEWING)
                .set("evaluation_report_id", report.getId()).set("revision_status", nextStatus)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "revision 并发更新失败");
        }
        return revisionView(requireRevision(workId, chapterId, revisionId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public RevisionView abandonRevision(
            Long workId,
            Long chapterId,
            Long revisionId,
            AbandonRevisionRequest request) {
        ChapterProseRevisionEntity revision = requireRevision(workId, chapterId, revisionId);
        if (request == null || request.expectedVersion() == null
                || !Objects.equals(request.expectedVersion(), revision.getVersion())) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "放弃 revision 必须提交当前版本");
        }
        if (!List.of(STATUS_DRAFT, STATUS_REVIEWING, STATUS_CONFIRMABLE).contains(revision.getRevisionStatus())) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "当前 revision 不能放弃");
        }
        int updated = proseRevisionMapper.update(null, new UpdateWrapper<ChapterProseRevisionEntity>()
                .eq("id", revisionId).eq("version", request.expectedVersion())
                .in("revision_status", STATUS_DRAFT, STATUS_REVIEWING, STATUS_CONFIRMABLE)
                .set("revision_status", STATUS_ABANDONED).setSql("version = version + 1"));
        if (updated != 1) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "revision 并发更新失败");
        }
        return revisionView(requireRevision(workId, chapterId, revisionId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public WorkspaceView createWorkspace(Long workId, CreateWorkspaceRequest request) {
        WorkEntity work = requireWorkForUpdate(workId);
        if (request == null || !StringUtils.hasText(request.idempotencyKey())) {
            throw badRequest("创建修订工作区必须提供 idempotencyKey");
        }
        WorkRevisionWorkspaceEntity existing = workspaceMapper.selectOne(
                new LambdaQueryWrapper<WorkRevisionWorkspaceEntity>()
                        .eq(WorkRevisionWorkspaceEntity::getWorkId, workId)
                        .eq(WorkRevisionWorkspaceEntity::getIdempotencyKey, request.idempotencyKey())
                        .eq(WorkRevisionWorkspaceEntity::getDeleted, 0));
        if (existing != null) {
            return workspaceView(existing);
        }
        WorkRevisionWorkspaceEntity active = workspaceMapper.selectOne(
                new LambdaQueryWrapper<WorkRevisionWorkspaceEntity>()
                        .eq(WorkRevisionWorkspaceEntity::getWorkId, workId)
                        .eq(WorkRevisionWorkspaceEntity::getCurrentMarker, 1)
                        .eq(WorkRevisionWorkspaceEntity::getDeleted, 0));
        if (active != null) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "作品已有活动修订工作区");
        }
        WorkRevisionWorkspaceEntity workspace = new WorkRevisionWorkspaceEntity();
        workspace.setWorkId(workId);
        workspace.setBaselineReleaseId(work.getCurrentStoryReleaseId());
        workspace.setBaselineWorkVersion(work.getVersion());
        workspace.setWorkspaceStatus(STATUS_DRAFT);
        workspace.setCurrentMarker(1);
        workspace.setBlockingItemsJson("[]");
        workspace.setIdempotencyKey(request.idempotencyKey());
        workspace.setCreatedBy(LOCAL_USER);
        workspace.setDeleted(0);
        workspace.setVersion(0);
        workspaceMapper.insert(workspace);
        return workspaceView(workspace);
    }

    @Override
    public WorkspaceView workspace(Long workId, Long workspaceId) {
        return workspaceView(requireWorkspace(workId, workspaceId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public WorkspaceView putWorkspaceChapter(
            Long workId,
            Long workspaceId,
            Long chapterId,
            PutWorkspaceChapterRequest request) {
        WorkRevisionWorkspaceEntity workspace = requireWorkspace(workId, workspaceId);
        if (request == null || request.proseRevisionId() == null || request.expectedVersion() == null
                || !Objects.equals(request.expectedVersion(), workspace.getVersion())
                || !STATUS_DRAFT.equals(workspace.getWorkspaceStatus())) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "工作区状态或版本不允许添加正文 revision");
        }
        ChapterEntity chapter = requireChapter(workId, chapterId);
        ChapterProseRevisionEntity revision = requireRevision(workId, chapterId, request.proseRevisionId());
        if (STATUS_ABANDONED.equals(revision.getRevisionStatus())) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "已放弃 revision 不能进入工作区");
        }
        StoryReleaseChapterEntity baseline = baselineMapping(workspace.getBaselineReleaseId(), chapterId);
        if (!Objects.equals(chapter.getCurrentProseRevisionId(), baseline == null ? null : baseline.getProseRevisionId())) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "章节发布基线已变化");
        }
        WorkRevisionWorkspaceChapterEntity existing = workspaceChapterMapper.selectOne(
                new LambdaQueryWrapper<WorkRevisionWorkspaceChapterEntity>()
                        .eq(WorkRevisionWorkspaceChapterEntity::getWorkspaceId, workspaceId)
                        .eq(WorkRevisionWorkspaceChapterEntity::getChapterId, chapterId)
                        .eq(WorkRevisionWorkspaceChapterEntity::getDeleted, 0));
        if (existing != null && Objects.equals(existing.getProseRevisionId(), revision.getId())) {
            return workspaceView(workspace);
        }
        if (existing == null) {
            existing = new WorkRevisionWorkspaceChapterEntity();
            existing.setWorkspaceId(workspaceId);
            existing.setWorkId(workId);
            existing.setChapterId(chapterId);
            existing.setProseRevisionId(revision.getId());
            existing.setBaselineProseRevisionId(baseline == null ? null : baseline.getProseRevisionId());
            existing.setBaselineChapterVersion(chapter.getVersion());
            existing.setEntryStatus("pending");
            existing.setDeleted(0);
            existing.setVersion(0);
            workspaceChapterMapper.insert(existing);
        } else {
            workspaceChapterMapper.update(null, new UpdateWrapper<WorkRevisionWorkspaceChapterEntity>()
                    .eq("id", existing.getId()).eq("version", existing.getVersion())
                    .set("prose_revision_id", revision.getId()).set("entry_status", "pending")
                    .setSql("version = version + 1"));
        }
        incrementWorkspaceVersion(workspaceId, request.expectedVersion(), STATUS_DRAFT);
        return workspaceView(requireWorkspace(workId, workspaceId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public WorkspaceView prepareWorkspace(
            Long workId,
            Long workspaceId,
            PrepareWorkspaceRequest request) {
        WorkRevisionWorkspaceEntity workspace = requireWorkspace(workId, workspaceId);
        if (request == null || request.expectedVersion() == null
                || !Objects.equals(request.expectedVersion(), workspace.getVersion())
                || !STATUS_DRAFT.equals(workspace.getWorkspaceStatus())) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "准备发布必须提交草稿工作区当前版本");
        }
        List<WorkRevisionWorkspaceChapterEntity> entries = workspaceEntries(workspaceId);
        List<String> blocking = new ArrayList<>(workspaceBlockingItems(workspace, entries));
        blocking.addAll(proseImpactHook.workspaceBlockingItems(workId, workspaceId));
        String nextStatus = blocking.isEmpty() ? WORKSPACE_READY : STATUS_DRAFT;
        int updated = workspaceMapper.update(null, new UpdateWrapper<WorkRevisionWorkspaceEntity>()
                .eq("id", workspaceId).eq("version", request.expectedVersion()).eq("workspace_status", STATUS_DRAFT)
                .set("workspace_status", nextStatus).set("blocking_items_json", json(blocking))
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "工作区并发更新失败");
        }
        updateEntryStatuses(entries, blocking.isEmpty() ? "ready" : "blocked");
        return workspaceView(requireWorkspace(workId, workspaceId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ReleaseView publishWorkspace(
            Long workId,
            Long workspaceId,
            PublishWorkspaceRequest request) {
        requirePublishCommand(request);
        StoryReleaseEntity repeated = releaseByIdempotency(workId, request.idempotencyKey());
        if (repeated != null) {
            return releaseView(requirePublishReplay(workId, workspaceId, repeated));
        }
        WorkRevisionWorkspaceEntity workspace = requireWorkspace(workId, workspaceId);
        if (!WORKSPACE_READY.equals(workspace.getWorkspaceStatus())
                || !Objects.equals(workspace.getVersion(), request.expectedVersion())) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "工作区尚未准备完成或版本已变化");
        }
        WorkEntity work = requireWorkForUpdate(workId);
        requireWorkspaceBaseline(workspace, work);
        List<ReleaseSelection> target = workspaceReleaseSelections(workspace);
        StoryReleaseEntity release = activateRelease(work, target, request.idempotencyKey(), null);
        int updated = workspaceMapper.update(null, new UpdateWrapper<WorkRevisionWorkspaceEntity>()
                .eq("id", workspaceId).eq("version", request.expectedVersion())
                .eq("workspace_status", WORKSPACE_READY)
                .set("workspace_status", WORKSPACE_PUBLISHED).set("published_release_id", release.getId())
                .set("current_marker", null).setSql("version = version + 1"));
        if (updated != 1) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "发布完成前工作区状态已变化");
        }
        return releaseView(release);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public WorkspaceView abandonWorkspace(
            Long workId,
            Long workspaceId,
            AbandonWorkspaceRequest request) {
        WorkRevisionWorkspaceEntity workspace = requireWorkspace(workId, workspaceId);
        if (request == null || request.expectedVersion() == null
                || !Objects.equals(request.expectedVersion(), workspace.getVersion())
                || !List.of(STATUS_DRAFT, WORKSPACE_READY).contains(workspace.getWorkspaceStatus())) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "工作区状态或版本不允许放弃");
        }
        int updated = workspaceMapper.update(null, new UpdateWrapper<WorkRevisionWorkspaceEntity>()
                .eq("id", workspaceId).eq("version", request.expectedVersion())
                .in("workspace_status", STATUS_DRAFT, WORKSPACE_READY)
                .set("workspace_status", STATUS_ABANDONED).set("current_marker", null)
                .set("abandoned_by", LOCAL_USER).setSql("version = version + 1"));
        if (updated != 1) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "工作区并发更新失败");
        }
        return workspaceView(requireWorkspace(workId, workspaceId));
    }

    @Override
    public ReleaseView release(Long workId, Long releaseId) {
        return releaseView(requireRelease(workId, releaseId));
    }

    @Override
    public List<ReleaseView> releases(Long workId) {
        requireWork(workId);
        return storyReleaseMapper.selectList(new LambdaQueryWrapper<StoryReleaseEntity>()
                        .eq(StoryReleaseEntity::getWorkId, workId)
                        .eq(StoryReleaseEntity::getDeleted, 0)
                        .orderByDesc(StoryReleaseEntity::getReleaseNo))
                .stream().map(this::releaseView).toList();
    }

    @Override
    public ReleaseDiff compareReleases(Long workId, Long baseReleaseId, Long targetReleaseId) {
        requireRelease(workId, baseReleaseId);
        requireRelease(workId, targetReleaseId);
        Map<Long, StoryReleaseChapterEntity> base = releaseMappingMap(baseReleaseId);
        Map<Long, StoryReleaseChapterEntity> target = releaseMappingMap(targetReleaseId);
        Set<Long> chapterIds = new LinkedHashSet<>(base.keySet());
        chapterIds.addAll(target.keySet());
        List<ReleaseDiffEntry> result = chapterIds.stream().sorted().map(chapterId -> {
            StoryReleaseChapterEntity left = base.get(chapterId);
            StoryReleaseChapterEntity right = target.get(chapterId);
            Long leftRevision = left == null ? null : left.getProseRevisionId();
            Long rightRevision = right == null ? null : right.getProseRevisionId();
            return new ReleaseDiffEntry(chapterId, leftRevision, rightRevision,
                    left == null ? null : left.getContentHash(), right == null ? null : right.getContentHash(),
                    !Objects.equals(leftRevision, rightRevision));
        }).toList();
        return new ReleaseDiff(baseReleaseId, targetReleaseId, result);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ReleaseView rollback(Long workId, Long targetReleaseId, RollbackReleaseRequest request) {
        requireRollbackCommand(request);
        StoryReleaseEntity repeated = releaseByIdempotency(workId, request.idempotencyKey());
        if (repeated != null) {
            return releaseView(requireRollbackReplay(targetReleaseId, request, repeated));
        }
        StoryReleaseEntity targetRelease = requireRelease(workId, targetReleaseId);
        WorkEntity work = requireWorkForUpdate(workId);
        if (!Objects.equals(work.getCurrentStoryReleaseId(), request.expectedCurrentReleaseId())
                || !Objects.equals(work.getVersion(), request.expectedWorkVersion())) {
            throw conflict(ErrorCode.STORY_RELEASE_CONFLICT, "回退基线或作品版本已变化");
        }
        StoryReleaseEntity current = requireRelease(workId, work.getCurrentStoryReleaseId());
        if (!Objects.equals(current.getCurrentMarker(), 1)) {
            throw conflict(ErrorCode.STORY_RELEASE_CONFLICT, "当前 Story Release 指针不一致");
        }
        List<ReleaseSelection> target = rollbackSelections(workId, current.getId(), targetRelease.getId());
        return releaseView(activateRelease(work, target, request.idempotencyKey(), targetReleaseId));
    }

    private StoryReleaseEntity activateRelease(
            WorkEntity work,
            List<ReleaseSelection> target,
            String idempotencyKey,
            Long rollbackOfReleaseId) {
        Long currentReleaseId = work.getCurrentStoryReleaseId();
        StoryReleaseEntity release = new StoryReleaseEntity();
        release.setWorkId(work.getId());
        release.setParentReleaseId(currentReleaseId);
        release.setRollbackOfReleaseId(rollbackOfReleaseId);
        release.setReleaseNo(nextReleaseNo(work.getId()));
        release.setReleaseStatus("preparing");
        release.setReleaseHash(releaseHash(target));
        release.setIdempotencyKey(idempotencyKey);
        release.setConfirmedBy(LOCAL_USER);
        release.setConfirmedAt(LocalDateTime.now());
        release.setDeleted(0);
        release.setVersion(0);
        storyReleaseMapper.insert(release);
        for (ReleaseSelection selection : target) {
            if (selection.revision() != null) {
                StoryReleaseChapterEntity mapping = new StoryReleaseChapterEntity();
                mapping.setReleaseId(release.getId());
                mapping.setWorkId(work.getId());
                mapping.setChapterId(selection.chapter().getId());
                mapping.setProseRevisionId(selection.revision().getId());
                mapping.setChapterNo(selection.chapter().getChapterNo());
                mapping.setContentHash(selection.revision().getContentHash());
                mapping.setDeleted(0);
                mapping.setVersion(0);
                releaseChapterMapper.insert(mapping);
            }
            switchChapterIfChanged(selection);
        }
        proseImpactHook.activateRelease(work.getId(), release.getId(), currentReleaseId, rollbackOfReleaseId);
        if (workMapper.updateCurrentStoryReleaseIfVersion(
                work.getId(), release.getId(), work.getVersion(), currentReleaseId) != 1) {
            throw conflict(ErrorCode.STORY_RELEASE_CONFLICT, "作品发布指针并发切换失败");
        }
        supersedeCurrentRelease(currentReleaseId);
        int activated = storyReleaseMapper.update(null, new UpdateWrapper<StoryReleaseEntity>()
                .eq("id", release.getId()).eq("version", 0).eq("release_status", "preparing")
                .set("release_status", "current").set("current_marker", 1).setSql("version = version + 1"));
        if (activated != 1) {
            throw conflict(ErrorCode.STORY_RELEASE_CONFLICT, "Story Release 激活失败");
        }
        updateRevisionPublicationStatuses(currentReleaseId, target);
        release.setReleaseStatus("current");
        release.setCurrentMarker(1);
        release.setVersion(1);
        return release;
    }

    private void switchChapterIfChanged(ReleaseSelection selection) {
        if (!selection.changed()) {
            return;
        }
        int updated;
        if (selection.revision() == null) {
            updated = chapterMapper.clearPublishedRevisionIfVersion(
                    selection.chapter().getId(), selection.chapter().getVersion(),
                    selection.expectedCurrentRevisionId());
        } else {
            updated = chapterMapper.updatePublishedRevisionIfVersion(
                    selection.chapter().getId(), selection.revision().getId(), selection.revision().getContent(),
                    selection.chapter().getVersion(), selection.expectedCurrentRevisionId());
        }
        if (updated != 1) {
            throw conflict(ErrorCode.STORY_RELEASE_CONFLICT,
                    "章节 " + selection.chapter().getId() + " 发布指针切换失败");
        }
    }

    private void supersedeCurrentRelease(Long currentReleaseId) {
        if (currentReleaseId == null) {
            return;
        }
        int updated = storyReleaseMapper.update(null, new UpdateWrapper<StoryReleaseEntity>()
                .eq("id", currentReleaseId).eq("current_marker", 1).eq("release_status", "current")
                .set("release_status", STATUS_SUPERSEDED).set("current_marker", null)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw conflict(ErrorCode.STORY_RELEASE_CONFLICT, "旧 Story Release 状态已变化");
        }
    }

    private void updateRevisionPublicationStatuses(Long oldReleaseId, List<ReleaseSelection> target) {
        List<Long> targetIds = target.stream().map(ReleaseSelection::revision).filter(Objects::nonNull)
                .map(ChapterProseRevisionEntity::getId).distinct().toList();
        if (oldReleaseId != null) {
            List<Long> oldIds = releaseMappings(oldReleaseId).stream()
                    .map(StoryReleaseChapterEntity::getProseRevisionId)
                    .filter(id -> !targetIds.contains(id)).distinct().toList();
            if (!oldIds.isEmpty()) {
                proseRevisionMapper.update(null, new UpdateWrapper<ChapterProseRevisionEntity>()
                        .in("id", oldIds).eq("revision_status", STATUS_PUBLISHED)
                        .set("revision_status", STATUS_SUPERSEDED).setSql("version = version + 1"));
            }
        }
        if (!targetIds.isEmpty()) {
            proseRevisionMapper.update(null, new UpdateWrapper<ChapterProseRevisionEntity>()
                    .in("id", targetIds)
                    .in("revision_status", STATUS_CONFIRMABLE, STATUS_SUPERSEDED, STATUS_PUBLISHED)
                    .set("revision_status", STATUS_PUBLISHED).setSql("version = version + 1"));
        }
    }

    private List<ReleaseSelection> workspaceReleaseSelections(WorkRevisionWorkspaceEntity workspace) {
        Map<Long, StoryReleaseChapterEntity> baseline = releaseMappingMap(workspace.getBaselineReleaseId());
        Map<Long, WorkRevisionWorkspaceChapterEntity> entries = workspaceEntries(workspace.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        WorkRevisionWorkspaceChapterEntity::getChapterId,
                        item -> item,
                        (left, right) -> right,
                        LinkedHashMap::new));
        if (entries.isEmpty()) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "工作区没有待发布章节");
        }
        Map<Long, ReleaseSelection> selections = new LinkedHashMap<>();
        for (StoryReleaseChapterEntity mapping : baseline.values()) {
            ChapterEntity chapter = requireChapter(workspace.getWorkId(), mapping.getChapterId());
            ChapterProseRevisionEntity revision = requireRevision(
                    workspace.getWorkId(), mapping.getChapterId(), mapping.getProseRevisionId());
            selections.put(chapter.getId(), new ReleaseSelection(chapter, revision, mapping.getProseRevisionId(), false));
        }
        for (WorkRevisionWorkspaceChapterEntity entry : entries.values()) {
            ChapterEntity chapter = requireChapterForUpdate(workspace.getWorkId(), entry.getChapterId());
            if (!Objects.equals(chapter.getVersion(), entry.getBaselineChapterVersion())
                    || !Objects.equals(chapter.getCurrentProseRevisionId(), entry.getBaselineProseRevisionId())) {
                throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "章节基线已变化，必须重建工作区");
            }
            ChapterProseRevisionEntity revision = requireRevision(
                    workspace.getWorkId(), entry.getChapterId(), entry.getProseRevisionId());
            requirePublishableRevision(revision);
            selections.put(chapter.getId(), new ReleaseSelection(
                    chapter, revision, entry.getBaselineProseRevisionId(),
                    !Objects.equals(entry.getBaselineProseRevisionId(), revision.getId())));
        }
        List<ChapterEntity> missing = activeChapters(workspace.getWorkId()).stream()
                .filter(chapter -> StringUtils.hasText(chapter.getContent()))
                .filter(chapter -> !selections.containsKey(chapter.getId())).toList();
        if (!missing.isEmpty()) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "Story Release 必须覆盖全部未进入基线的正文章节");
        }
        return selections.values().stream()
                .sorted(Comparator.comparing(item -> item.chapter().getChapterNo())).toList();
    }

    private List<ReleaseSelection> rollbackSelections(Long workId, Long currentReleaseId, Long targetReleaseId) {
        Map<Long, StoryReleaseChapterEntity> current = releaseMappingMap(currentReleaseId);
        Map<Long, StoryReleaseChapterEntity> target = releaseMappingMap(targetReleaseId);
        List<ReleaseSelection> result = new ArrayList<>();
        for (StoryReleaseChapterEntity mapping : target.values()) {
            ChapterEntity chapter = requireChapterForUpdate(workId, mapping.getChapterId());
            StoryReleaseChapterEntity currentMapping = current.get(mapping.getChapterId());
            Long expectedCurrent = currentMapping == null ? null : currentMapping.getProseRevisionId();
            if (!Objects.equals(chapter.getCurrentProseRevisionId(), expectedCurrent)) {
                throw conflict(ErrorCode.STORY_RELEASE_CONFLICT, "章节当前指针与 Story Release 不一致");
            }
            ChapterProseRevisionEntity revision = requireRevision(workId, chapter.getId(), mapping.getProseRevisionId());
            if (!Objects.equals(revision.getContentHash(), mapping.getContentHash())) {
                throw conflict(ErrorCode.STORY_RELEASE_CONFLICT, "目标 Story Release 内容哈希已损坏");
            }
            result.add(new ReleaseSelection(chapter, revision, expectedCurrent,
                    !Objects.equals(expectedCurrent, revision.getId())));
        }
        for (StoryReleaseChapterEntity mapping : current.values()) {
            if (target.containsKey(mapping.getChapterId())) {
                continue;
            }
            ChapterEntity chapter = requireChapterForUpdate(workId, mapping.getChapterId());
            if (!Objects.equals(chapter.getCurrentProseRevisionId(), mapping.getProseRevisionId())) {
                throw conflict(ErrorCode.STORY_RELEASE_CONFLICT, "待下线章节指针与当前 Story Release 不一致");
            }
            ChapterProseRevisionEntity revision = requireRevision(
                    workId, mapping.getChapterId(), mapping.getProseRevisionId());
            if (!Objects.equals(revision.getContentHash(), mapping.getContentHash())) {
                throw conflict(ErrorCode.STORY_RELEASE_CONFLICT, "当前 Story Release 内容哈希已损坏");
            }
            result.add(new ReleaseSelection(chapter, null, mapping.getProseRevisionId(), true));
        }
        return result.stream().sorted(Comparator.comparing(item -> item.chapter().getChapterNo())).toList();
    }

    private List<String> workspaceBlockingItems(
            WorkRevisionWorkspaceEntity workspace,
            List<WorkRevisionWorkspaceChapterEntity> entries) {
        List<String> blocking = new ArrayList<>();
        if (entries.isEmpty()) {
            blocking.add("workspace_has_no_revision");
        }
        Set<Long> covered = new LinkedHashSet<>(releaseMappingMap(workspace.getBaselineReleaseId()).keySet());
        entries.stream().map(WorkRevisionWorkspaceChapterEntity::getChapterId).forEach(covered::add);
        activeChapters(workspace.getWorkId()).stream().filter(chapter -> StringUtils.hasText(chapter.getContent()))
                .filter(chapter -> !covered.contains(chapter.getId()))
                .forEach(chapter -> blocking.add("release_missing_chapter:" + chapter.getId()));
        for (WorkRevisionWorkspaceChapterEntity entry : entries) {
            ChapterEntity chapter = requireChapter(workspace.getWorkId(), entry.getChapterId());
            if (!Objects.equals(chapter.getVersion(), entry.getBaselineChapterVersion())
                    || !Objects.equals(chapter.getCurrentProseRevisionId(), entry.getBaselineProseRevisionId())) {
                blocking.add("chapter_baseline_changed:" + entry.getChapterId());
                continue;
            }
            try {
                requirePublishableRevision(requireRevision(
                        workspace.getWorkId(), entry.getChapterId(), entry.getProseRevisionId()));
            } catch (BusinessException exception) {
                blocking.add("revision_not_publishable:" + entry.getProseRevisionId());
            }
        }
        return List.copyOf(blocking);
    }

    private void requirePublishableRevision(ChapterProseRevisionEntity revision) {
        if (!STATUS_CONFIRMABLE.equals(revision.getRevisionStatus()) || revision.getEvaluationReportId() == null
                || !Objects.equals(revision.getContentHash(), hash(revision.getContent()))) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "revision 尚未满足发布条件");
        }
        ChapterGenerationEvaluationReportEntity report = requireMatchingReport(
                revision, revision.getEvaluationReportId());
        if (!REPORT_READY.equals(report.getReportStatus())
                || !ADOPTABLE_CONCLUSIONS.contains(report.getConclusion())) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "revision 评价未通过");
        }
        evaluationService.requireAdoptable(revision.getChapterId(), report.getGenerationId());
    }

    private void requireWorkspaceBaseline(WorkRevisionWorkspaceEntity workspace, WorkEntity work) {
        if (!Objects.equals(workspace.getBaselineReleaseId(), work.getCurrentStoryReleaseId())
                || !Objects.equals(workspace.getBaselineWorkVersion(), work.getVersion())) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "作品或 Story Release 基线已变化");
        }
    }

    private void incrementWorkspaceVersion(Long workspaceId, Integer expectedVersion, String expectedStatus) {
        int updated = workspaceMapper.update(null, new UpdateWrapper<WorkRevisionWorkspaceEntity>()
                .eq("id", workspaceId).eq("version", expectedVersion).eq("workspace_status", expectedStatus)
                .set("blocking_items_json", "[]").setSql("version = version + 1"));
        if (updated != 1) {
            throw conflict(ErrorCode.REVISION_WORKSPACE_CONFLICT, "工作区并发更新失败");
        }
    }

    private void updateEntryStatuses(List<WorkRevisionWorkspaceChapterEntity> entries, String status) {
        if (entries.isEmpty()) {
            return;
        }
        workspaceChapterMapper.update(null, new UpdateWrapper<WorkRevisionWorkspaceChapterEntity>()
                .in("id", entries.stream().map(WorkRevisionWorkspaceChapterEntity::getId).toList())
                .set("entry_status", status).setSql("version = version + 1"));
    }

    private String evaluationBindingStatus(ChapterGenerationEvaluationReportEntity report) {
        if (List.of(REPORT_QUEUED, REPORT_RUNNING).contains(report.getReportStatus())) {
            return STATUS_REVIEWING;
        }
        if (REPORT_READY.equals(report.getReportStatus())
                && ADOPTABLE_CONCLUSIONS.contains(report.getConclusion())) {
            return STATUS_CONFIRMABLE;
        }
        throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "评价报告尚未形成可确认结论");
    }

    private ChapterGenerationEvaluationReportEntity requireMatchingReport(
            ChapterProseRevisionEntity revision,
            Long reportId) {
        ChapterGenerationEvaluationReportEntity report = evaluationReportMapper.selectById(reportId);
        if (report == null || Integer.valueOf(1).equals(report.getDeleted())
                || !Objects.equals(report.getWorkId(), revision.getWorkId())
                || !Objects.equals(report.getChapterId(), revision.getChapterId())
                || revision.getSourceGenerationId() == null
                || !Objects.equals(report.getGenerationId(), revision.getSourceGenerationId())
                || report.getGenerationSceneId() != null
                || !Objects.equals(report.getContentHash(), revision.getContentHash())) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT,
                    "整章评价报告与 revision 来源 generation、正文哈希或归属不匹配");
        }
        if (revision.getSourceBoundedRevisionId() != null) {
            BoundedChapterRevisionEntity bounded = boundedRevisionMapper.selectById(
                    revision.getSourceBoundedRevisionId());
            if (bounded == null || Integer.valueOf(1).equals(bounded.getDeleted())
                    || !Objects.equals(bounded.getWorkId(), revision.getWorkId())
                    || !Objects.equals(bounded.getChapterId(), revision.getChapterId())
                    || !Objects.equals(bounded.getResultGenerationId(), revision.getSourceGenerationId())
                    || !Objects.equals(bounded.getResultReportId(), reportId)
                    || !isBoundedCandidateReady(bounded, report)) {
                throw conflict(ErrorCode.PROSE_REVISION_CONFLICT,
                        "#106 有界修订必须保持 candidate_ready，且绑定其 resultReportId");
            }
        }
        return report;
    }

    private ChapterGenerationEntity sourceGeneration(Long workId, Long chapterId, Long generationId) {
        if (generationId == null) {
            return null;
        }
        ChapterGenerationEntity generation = generationMapper.selectById(generationId);
        if (generation == null || Integer.valueOf(1).equals(generation.getDeleted())
                || !Objects.equals(workId, generation.getWorkId())
                || !Objects.equals(chapterId, generation.getChapterId())
                || !StringUtils.hasText(generation.getGeneratedContent())) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "来源正文候选不存在或归属不一致");
        }
        return generation;
    }

    private BoundedChapterRevisionEntity sourceBoundedRevision(
            Long workId,
            Long chapterId,
            Long boundedRevisionId,
            ChapterGenerationEntity generation) {
        boolean boundedAssembly = generation != null
                && ASSEMBLY_BOUNDED_REVISION.equals(generation.getContentAssemblyMode());
        if (boundedRevisionId == null) {
            if (boundedAssembly) {
                throw conflict(ErrorCode.PROSE_REVISION_CONFLICT,
                        "bounded_revision generation 必须提供 sourceBoundedRevisionId");
            }
            return null;
        }
        if (!boundedAssembly) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT,
                    "sourceBoundedRevisionId 只能绑定 bounded_revision generation");
        }
        BoundedChapterRevisionEntity bounded = boundedRevisionMapper.selectById(boundedRevisionId);
        if (bounded == null || Integer.valueOf(1).equals(bounded.getDeleted())
                || !Objects.equals(workId, bounded.getWorkId())
                || !Objects.equals(chapterId, bounded.getChapterId())
                || generation == null || !Objects.equals(generation.getId(), bounded.getResultGenerationId())) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "#106 有界修订与来源正文候选不匹配");
        }
        ChapterGenerationEvaluationReportEntity report = bounded.getResultReportId() == null
                ? null : evaluationReportMapper.selectById(bounded.getResultReportId());
        if (!isBoundedCandidateReady(bounded, report)) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT,
                    "#106 有界修订尚未达到 candidate_ready，不能创建正文 revision");
        }
        return bounded;
    }

    private boolean isBoundedCandidateReady(
            BoundedChapterRevisionEntity bounded,
            ChapterGenerationEvaluationReportEntity report) {
        boolean eligibleStatus = BOUNDED_CANDIDATE_READY.equals(bounded.getRevisionStatus())
                || BOUNDED_RE_EVALUATING.equals(bounded.getRevisionStatus());
        return eligibleStatus && report != null && !Integer.valueOf(1).equals(report.getDeleted())
                && Objects.equals(report.getId(), bounded.getResultReportId())
                && Objects.equals(report.getWorkId(), bounded.getWorkId())
                && Objects.equals(report.getChapterId(), bounded.getChapterId())
                && Objects.equals(report.getGenerationId(), bounded.getResultGenerationId())
                && report.getGenerationSceneId() == null
                && REPORT_READY.equals(report.getReportStatus())
                && ADOPTABLE_CONCLUSIONS.contains(report.getConclusion())
                && Objects.equals(report.getContentHash(), bounded.getResultContentHash());
    }

    private String revisionContent(String requestedContent, ChapterGenerationEntity generation) {
        String content = requestedContent;
        if (content == null && generation != null) {
            content = generation.getGeneratedContent();
        }
        if (!StringUtils.hasText(content) || content.length() > MAX_CONTENT_LENGTH) {
            throw badRequest("正文 revision 内容为空或超过长度限制");
        }
        return content;
    }

    private String revisionOrigin(
            CreateRevisionRequest request,
            ChapterGenerationEntity generation,
            BoundedChapterRevisionEntity bounded,
            String content) {
        if (bounded != null && Objects.equals(content, generation.getGeneratedContent())) {
            return "bounded_revision";
        }
        if (generation != null && Objects.equals(content, generation.getGeneratedContent())) {
            return "generation";
        }
        return "manual";
    }

    private int nextRevisionNo(Long chapterId) {
        ChapterProseRevisionEntity latest = proseRevisionMapper.selectOne(
                new LambdaQueryWrapper<ChapterProseRevisionEntity>()
                        .eq(ChapterProseRevisionEntity::getChapterId, chapterId)
                        .eq(ChapterProseRevisionEntity::getDeleted, 0)
                        .orderByDesc(ChapterProseRevisionEntity::getRevisionNo).last("LIMIT 1"));
        return latest == null ? 1 : latest.getRevisionNo() + 1;
    }

    private int nextReleaseNo(Long workId) {
        StoryReleaseEntity latest = storyReleaseMapper.selectOne(new LambdaQueryWrapper<StoryReleaseEntity>()
                .eq(StoryReleaseEntity::getWorkId, workId).eq(StoryReleaseEntity::getDeleted, 0)
                .orderByDesc(StoryReleaseEntity::getReleaseNo).last("LIMIT 1"));
        return latest == null ? 1 : latest.getReleaseNo() + 1;
    }

    private StoryReleaseChapterEntity baselineMapping(Long baselineReleaseId, Long chapterId) {
        if (baselineReleaseId == null) {
            return null;
        }
        return releaseChapterMapper.selectOne(new LambdaQueryWrapper<StoryReleaseChapterEntity>()
                .eq(StoryReleaseChapterEntity::getReleaseId, baselineReleaseId)
                .eq(StoryReleaseChapterEntity::getChapterId, chapterId)
                .eq(StoryReleaseChapterEntity::getDeleted, 0));
    }

    private List<WorkRevisionWorkspaceChapterEntity> workspaceEntries(Long workspaceId) {
        return workspaceChapterMapper.selectList(new LambdaQueryWrapper<WorkRevisionWorkspaceChapterEntity>()
                .eq(WorkRevisionWorkspaceChapterEntity::getWorkspaceId, workspaceId)
                .eq(WorkRevisionWorkspaceChapterEntity::getDeleted, 0)
                .orderByAsc(WorkRevisionWorkspaceChapterEntity::getChapterId));
    }

    private List<StoryReleaseChapterEntity> releaseMappings(Long releaseId) {
        if (releaseId == null) {
            return List.of();
        }
        return releaseChapterMapper.selectList(new LambdaQueryWrapper<StoryReleaseChapterEntity>()
                .eq(StoryReleaseChapterEntity::getReleaseId, releaseId)
                .eq(StoryReleaseChapterEntity::getDeleted, 0)
                .orderByAsc(StoryReleaseChapterEntity::getChapterNo));
    }

    private Map<Long, StoryReleaseChapterEntity> releaseMappingMap(Long releaseId) {
        Map<Long, StoryReleaseChapterEntity> result = new LinkedHashMap<>();
        for (StoryReleaseChapterEntity item : releaseMappings(releaseId)) {
            result.put(item.getChapterId(), item);
        }
        return result;
    }

    private List<ChapterEntity> activeChapters(Long workId) {
        return chapterMapper.selectList(new LambdaQueryWrapper<ChapterEntity>()
                .eq(ChapterEntity::getWorkId, workId).eq(ChapterEntity::getDeleted, 0)
                .orderByAsc(ChapterEntity::getChapterNo));
    }

    private String releaseHash(List<ReleaseSelection> selections) {
        StringBuilder value = new StringBuilder();
        selections.stream().filter(item -> item.revision() != null)
                .sorted(Comparator.comparing(item -> item.chapter().getChapterNo()))
                .forEach(item -> value.append(item.chapter().getId()).append(':')
                        .append(item.revision().getId()).append(':')
                        .append(item.revision().getContentHash()).append('\n'));
        return hash(value.toString());
    }

    private WorkEntity requireWork(Long workId) {
        WorkEntity work = workMapper.selectById(workId);
        if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return work;
    }

    private WorkEntity requireWorkForUpdate(Long workId) {
        WorkEntity work = workMapper.selectByIdForUpdate(workId);
        if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return work;
    }

    private ChapterEntity requireChapter(Long workId, Long chapterId) {
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())
                || !Objects.equals(workId, chapter.getWorkId())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private ChapterEntity requireChapterForUpdate(Long workId, Long chapterId) {
        ChapterEntity chapter = chapterMapper.selectByIdForUpdate(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())
                || !Objects.equals(workId, chapter.getWorkId())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private ChapterProseRevisionEntity requireRevision(Long workId, Long chapterId, Long revisionId) {
        ChapterProseRevisionEntity revision = proseRevisionMapper.selectById(revisionId);
        if (revision == null || Integer.valueOf(1).equals(revision.getDeleted())
                || !Objects.equals(workId, revision.getWorkId())
                || !Objects.equals(chapterId, revision.getChapterId())) {
            throw new BusinessException(ErrorCode.PROSE_REVISION_NOT_FOUND, "正文 revision 不存在");
        }
        return revision;
    }

    private WorkRevisionWorkspaceEntity requireWorkspace(Long workId, Long workspaceId) {
        WorkRevisionWorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || Integer.valueOf(1).equals(workspace.getDeleted())
                || !Objects.equals(workId, workspace.getWorkId())) {
            throw new BusinessException(ErrorCode.REVISION_WORKSPACE_NOT_FOUND, "作品修订工作区不存在");
        }
        return workspace;
    }

    private StoryReleaseEntity requireRelease(Long workId, Long releaseId) {
        StoryReleaseEntity release = releaseId == null ? null : storyReleaseMapper.selectById(releaseId);
        if (release == null || Integer.valueOf(1).equals(release.getDeleted())
                || !Objects.equals(workId, release.getWorkId())) {
            throw new BusinessException(ErrorCode.STORY_RELEASE_NOT_FOUND, "Story Release 不存在");
        }
        return release;
    }

    private StoryReleaseEntity releaseByIdempotency(Long workId, String idempotencyKey) {
        return storyReleaseMapper.selectOne(new LambdaQueryWrapper<StoryReleaseEntity>()
                .eq(StoryReleaseEntity::getWorkId, workId)
                .eq(StoryReleaseEntity::getIdempotencyKey, idempotencyKey)
                .eq(StoryReleaseEntity::getDeleted, 0));
    }

    private StoryReleaseEntity requirePublishReplay(
            Long workId,
            Long workspaceId,
            StoryReleaseEntity repeated) {
        WorkRevisionWorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || Integer.valueOf(1).equals(workspace.getDeleted())
                || !Objects.equals(workspace.getWorkId(), workId)
                || !WORKSPACE_PUBLISHED.equals(workspace.getWorkspaceStatus())
                || !Objects.equals(workspace.getPublishedReleaseId(), repeated.getId())
                || repeated.getRollbackOfReleaseId() != null) {
            throw conflict(ErrorCode.STORY_RELEASE_CONFLICT, "幂等键已绑定其他发布操作");
        }
        return repeated;
    }

    private StoryReleaseEntity requireRollbackReplay(
            Long targetReleaseId,
            RollbackReleaseRequest request,
            StoryReleaseEntity repeated) {
        if (!Objects.equals(repeated.getRollbackOfReleaseId(), targetReleaseId)
                || !Objects.equals(repeated.getParentReleaseId(), request.expectedCurrentReleaseId())) {
            throw conflict(ErrorCode.STORY_RELEASE_CONFLICT, "幂等键已绑定其他回退操作");
        }
        return repeated;
    }

    private void requireCreateRevisionRequest(CreateRevisionRequest request) {
        if (request == null || !StringUtils.hasText(request.idempotencyKey())) {
            throw badRequest("创建正文 revision 必须提供 idempotencyKey");
        }
    }

    private void requireSameRevisionInput(ChapterProseRevisionEntity existing, CreateRevisionRequest request) {
        boolean identityChanged = !Objects.equals(existing.getParentRevisionId(), request.parentRevisionId())
                || !Objects.equals(existing.getSourceGenerationId(), request.sourceGenerationId())
                || !Objects.equals(existing.getSourceBoundedRevisionId(), request.sourceBoundedRevisionId());
        boolean contentChanged = request.content() != null
                && !Objects.equals(existing.getContentHash(), hash(request.content()));
        if (identityChanged || contentChanged) {
            throw conflict(ErrorCode.PROSE_REVISION_CONFLICT, "幂等键已绑定不同正文 revision 输入");
        }
    }

    private void requirePublishCommand(PublishWorkspaceRequest request) {
        if (request == null || request.expectedVersion() == null
                || !StringUtils.hasText(request.idempotencyKey())
                || !Boolean.TRUE.equals(request.userConfirmed())) {
            throw badRequest("发布必须提交 expectedVersion、idempotencyKey 和用户显式确认");
        }
    }

    private void requireRollbackCommand(RollbackReleaseRequest request) {
        if (request == null || request.expectedCurrentReleaseId() == null || request.expectedWorkVersion() == null
                || !StringUtils.hasText(request.idempotencyKey())
                || !Boolean.TRUE.equals(request.userConfirmed())) {
            throw badRequest("回退必须提交当前 release、作品版本、idempotencyKey 和用户显式确认");
        }
    }

    private RevisionView revisionView(ChapterProseRevisionEntity item) {
        return new RevisionView(item.getId(), item.getWorkId(), item.getChapterId(), item.getParentRevisionId(),
                item.getSourceGenerationId(), item.getSourceBoundedRevisionId(), item.getSourceSnapshotId(),
                item.getEvaluationReportId(), item.getRevisionNo(), item.getRevisionOrigin(), item.getRevisionStatus(),
                item.getContent(), item.getContentHash(), item.getVersion(), item.getGmtCreate(), item.getGmtModified());
    }

    private WorkspaceView workspaceView(WorkRevisionWorkspaceEntity item) {
        List<WorkspaceChapterView> chapters = workspaceEntries(item.getId()).stream().map(entry ->
                new WorkspaceChapterView(entry.getId(), entry.getChapterId(), entry.getProseRevisionId(),
                        entry.getBaselineProseRevisionId(), entry.getBaselineChapterVersion(), entry.getEntryStatus(),
                        entry.getVersion())).toList();
        return new WorkspaceView(item.getId(), item.getWorkId(), item.getBaselineReleaseId(),
                item.getPublishedReleaseId(), item.getBaselineWorkVersion(), item.getWorkspaceStatus(),
                readList(item.getBlockingItemsJson()), proseImpactHook.workspaceSummary(item.getWorkId(), item.getId()),
                chapters, item.getVersion(), item.getGmtCreate(),
                item.getGmtModified());
    }

    private ReleaseView releaseView(StoryReleaseEntity item) {
        List<ReleaseChapterView> chapters = releaseMappings(item.getId()).stream()
                .map(mapping -> new ReleaseChapterView(mapping.getChapterId(), mapping.getChapterNo(),
                        mapping.getProseRevisionId(), mapping.getContentHash())).toList();
        return new ReleaseView(item.getId(), item.getWorkId(), item.getParentReleaseId(), item.getRollbackOfReleaseId(),
                item.getReleaseNo(), item.getReleaseStatus(), item.getReleaseHash(), chapters, item.getVersion(),
                item.getConfirmedAt(), item.getGmtCreate());
    }

    private List<String> readList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "修订工作区阻塞项无法读取", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "修订工作区数据无法序列化", exception);
        }
    }

    private String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private BusinessException conflict(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }

    private record ReleaseSelection(
            ChapterEntity chapter,
            ChapterProseRevisionEntity revision,
            Long expectedCurrentRevisionId,
            boolean changed) {
    }
}
