package com.dugnan.moqi.release;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.CreateEvaluationRequest;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationReportView;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.RetryEvaluationRequest;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.service.GenerationEvaluationService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.release.entity.ChapterProseRevisionEntity;
import com.dugnan.moqi.release.mapper.ChapterProseRevisionMapper;
import com.dugnan.moqi.sourcechain.entity.ChapterAssetSourceSnapshotEntity;
import com.dugnan.moqi.sourcechain.mapper.ChapterAssetSourceSnapshotMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-09-15
 * @description 冻结修订正文的独立评价来源并复用整章评价及持久化重试。
 */
@Service
public class RevisionQualityService {
    private final WorkMapper works;
    private final ChapterMapper chapters;
    private final ChapterProseRevisionMapper revisions;
    private final ChapterGenerationMapper generations;
    private final ChapterAssetSourceSnapshotMapper snapshots;
    private final GenerationEvaluationService evaluations;

    public RevisionQualityService(WorkMapper works, ChapterMapper chapters, ChapterProseRevisionMapper revisions,
            ChapterGenerationMapper generations, ChapterAssetSourceSnapshotMapper snapshots,
            GenerationEvaluationService evaluations) {
        this.works = works;
        this.chapters = chapters;
        this.revisions = revisions;
        this.generations = generations;
        this.snapshots = snapshots;
        this.evaluations = evaluations;
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public EvaluationReportView start(Long workId, Long chapterId, Long revisionId, Integer expectedVersion) {
        ChapterProseRevisionEntity revision = writable(workId, chapterId, revisionId);
        if (expectedVersion == null) {
            throw conflict("质量检查需要当前修订版本");
        }
        Long generationId = revision.getQualityGenerationId();
        if (generationId == null && revision.getEvaluationReportId() != null) {
            requireGeneration(revision, revision.getSourceGenerationId(), true);
            return evaluations.get(chapterId, revision.getSourceGenerationId(), revision.getEvaluationReportId());
        }
        if (generationId != null) {
            requireGeneration(revision, generationId, true);
            EvaluationReportView existing = evaluations.latest(chapterId, generationId, null);
            if (existing != null) {
                return existing;
            }
        }
        if (!Objects.equals(expectedVersion, revision.getVersion())) {
            throw conflict("修订版本已变化，请刷新后重试");
        }
        if (generationId == null) {
            ChapterGenerationEntity source = requireGeneration(revision, revision.getSourceGenerationId(), false);
            if (!"manual".equals(revision.getRevisionOrigin())) {
                return evaluations.create(chapterId, source.getId(), new CreateEvaluationRequest(null,
                        "revision-quality:" + revisionId));
            }
            ChapterGenerationEntity snapshot = freeze(revision, source);
            generations.insert(snapshot);
            copySource(source, snapshot);
            int updated = revisions.update(null, new UpdateWrapper<ChapterProseRevisionEntity>()
                    .eq("id", revisionId).eq("version", expectedVersion).isNull("quality_generation_id")
                    .set("quality_generation_id", snapshot.getId()).setSql("version = version + 1"));
            if (updated != 1) {
                throw conflict("修订评价来源已变化");
            }
            generationId = snapshot.getId();
        }
        return evaluations.create(chapterId, generationId,
                new CreateEvaluationRequest(null, "revision-quality:" + revisionId));
    }

    public EvaluationReportView latest(Long workId, Long chapterId, Long revisionId) {
        ChapterProseRevisionEntity revision = read(workId, chapterId, revisionId, false);
        Long generationId = revision.getQualityGenerationId();
        if (generationId == null && "manual".equals(revision.getRevisionOrigin())
                && revision.getEvaluationReportId() == null) {
            return null;
        }
        generationId = generationId == null ? revision.getSourceGenerationId() : generationId;
        if (generationId == null) {
            return null;
        }
        requireGeneration(revision, generationId, true);
        return evaluations.latest(chapterId, generationId, null);
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public EvaluationReportView retry(Long workId, Long chapterId, Long revisionId,
            Long reportId, RetryEvaluationRequest request) {
        ChapterProseRevisionEntity revision = writable(workId, chapterId, revisionId);
        Long generationId = revision.getQualityGenerationId() == null
                ? revision.getSourceGenerationId() : revision.getQualityGenerationId();
        requireGeneration(revision, generationId, true);
        EvaluationReportView report = evaluations.get(chapterId, generationId, reportId);
        EvaluationReportView latest = evaluations.latest(chapterId, generationId, null);
        if (latest == null || !Objects.equals(latest.id(), reportId) || !report.retryable()
                || request == null || !Objects.equals(request.expectedAttempt(), report.currentAttempt())) {
            throw conflict("质量检查状态已变化，请刷新后重试");
        }
        evaluations.retry(chapterId, generationId, reportId, request);
        return evaluations.get(chapterId, generationId, reportId);
    }

    private ChapterProseRevisionEntity writable(Long workId, Long chapterId, Long revisionId) {
        WorkEntity work = works.selectOne(new LambdaQueryWrapper<WorkEntity>()
                .eq(WorkEntity::getId, workId).eq(WorkEntity::getDeleted, 0).last("FOR UPDATE"));
        ChapterEntity chapter = chapters.selectOne(new LambdaQueryWrapper<ChapterEntity>()
                .eq(ChapterEntity::getId, chapterId).eq(ChapterEntity::getWorkId, workId)
                .eq(ChapterEntity::getDeleted, 0).last("FOR UPDATE"));
        ChapterProseRevisionEntity revision = read(workId, chapterId, revisionId, true);
        if (work == null || chapter == null || !List.of("draft", "reviewing").contains(revision.getRevisionStatus())
                || !Objects.equals(chapter.getCurrentProseRevisionId(), revision.getParentRevisionId())
                || !Objects.equals(hash(revision.getContent()), revision.getContentHash())) {
            throw conflict("修订或发布基线已变化，请刷新后重试");
        }
        return revision;
    }

    private ChapterProseRevisionEntity read(Long workId, Long chapterId, Long revisionId, boolean lock) {
        ChapterProseRevisionEntity revision = revisions.selectOne(new LambdaQueryWrapper<ChapterProseRevisionEntity>()
                .eq(ChapterProseRevisionEntity::getId, revisionId)
                .eq(ChapterProseRevisionEntity::getWorkId, workId)
                .eq(ChapterProseRevisionEntity::getChapterId, chapterId)
                .eq(ChapterProseRevisionEntity::getDeleted, 0).last(lock ? "FOR UPDATE" : "LIMIT 1"));
        if (revision == null) {
            throw conflict("修订不存在或归属不匹配");
        }
        return revision;
    }

    private ChapterGenerationEntity requireGeneration(ChapterProseRevisionEntity revision, Long id, boolean exact) {
        ChapterGenerationEntity source = id == null ? null : generations.selectById(id);
        if (source == null || !Integer.valueOf(0).equals(source.getDeleted())
                || !Objects.equals(source.getWorkId(), revision.getWorkId())
                || !Objects.equals(source.getChapterId(), revision.getChapterId())
                || (exact && !Objects.equals(hash(source.getGeneratedContent()), revision.getContentHash()))) {
            throw conflict("修订缺少匹配正文的评价来源");
        }
        return source;
    }

    private ChapterGenerationEntity freeze(ChapterProseRevisionEntity revision, ChapterGenerationEntity source) {
        ChapterGenerationEntity snapshot = new ChapterGenerationEntity();
        snapshot.setWorkId(source.getWorkId());
        snapshot.setChapterId(source.getChapterId());
        snapshot.setBriefId(source.getBriefId());
        snapshot.setOutlineId(source.getOutlineId());
        snapshot.setOutlineRevision(source.getOutlineRevision());
        snapshot.setChapterPlanVersionId(source.getChapterPlanVersionId());
        snapshot.setBaseGenerationId(source.getId());
        snapshot.setGenerationStatus("candidate_snapshot");
        snapshot.setGenerationMode(source.getGenerationMode());
        snapshot.setSelectionMode(source.getSelectionMode());
        snapshot.setIdempotencyKey("revision-quality:" + revision.getId());
        snapshot.setLengthPreset(source.getLengthPreset());
        snapshot.setCustomWordCount(source.getCustomWordCount());
        snapshot.setBasisSnapshotJson(source.getBasisSnapshotJson());
        snapshot.setExecutionConfigJson(source.getExecutionConfigJson());
        snapshot.setGeneratedContent(revision.getContent());
        snapshot.setContentAssemblyMode("revision_quality");
        snapshot.setCohesionStatus(source.getCohesionStatus());
        snapshot.setGenerationTemplateVersion("revision-quality-v1");
        snapshot.setGenerationFinishReason("explicit_revision_review");
        snapshot.setWordCount(revision.getContent().length());
        snapshot.setValidityStatus(source.getValidityStatus());
        snapshot.setValidityReasonCodesJson(source.getValidityReasonCodesJson());
        snapshot.setDeleted(0);
        snapshot.setVersion(0);
        return snapshot;
    }

    private void copySource(ChapterGenerationEntity source, ChapterGenerationEntity target) {
        if (source.getSourceSnapshotId() == null) {
            return;
        }
        ChapterAssetSourceSnapshotEntity original = snapshots.selectById(source.getSourceSnapshotId());
        if (original == null || !Integer.valueOf(0).equals(original.getDeleted())
                || !Objects.equals(original.getWorkId(), source.getWorkId())
                || !Objects.equals(original.getChapterId(), source.getChapterId())
                || !"generation".equals(original.getAssetType())
                || !Objects.equals(original.getAssetId(), source.getId())) {
            throw conflict("修订原始上下文快照无效");
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
        snapshots.insert(snapshot);
        target.setSourceSnapshotId(snapshot.getId());
        generations.updateById(target);
    }

    private static String hash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNullElse(content, "").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.PROSE_REVISION_CONFLICT, message);
    }
}
