package com.dugnan.moqi.chapter.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.GenerationTaskSummary;
import com.dugnan.moqi.chapter.dto.SceneGenerationModels.CreateSceneGenerationRequest;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterProseCandidateMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;

/**
 * @author dgn
 * @date 2026-10-20
 * @description 冻结稳定正文生成目标，并在完整输出后以短事务安全写回同一对象。
 */
@Service
public class ProseGenerationTargetService {

    private static final String MODE_REPLACE = "replace_current";
    private static final String MODE_NEW = "new_candidate";
    private static final String SELECTION_ALL = "all";
    private static final String CANDIDATE_ACTIVE = "active";
    private static final String CANDIDATE_UNADOPTED = "unadopted";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_PREVIEW = "preview";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELED = "canceled";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_CONFLICT = "conflict";
    private static final String CANDIDATE_PREFIX = "candidate:";
    private static final String FORMAL_PREFIX = "formal:";
    private final ChapterMapper chapterMapper;
    private final ChapterProseCandidateMapper candidateMapper;
    private final ChapterGenerationMapper generationMapper;
    private final ObjectMapper objectMapper;

    public ProseGenerationTargetService(ChapterMapper chapterMapper,
            ChapterProseCandidateMapper candidateMapper, ChapterGenerationMapper generationMapper,
            ObjectMapper objectMapper) {
        this.chapterMapper = chapterMapper;
        this.candidateMapper = candidateMapper;
        this.generationMapper = generationMapper;
        this.objectMapper = objectMapper;
    }

    /** 调用方持有创建事务；章节锁串行化幂等查询、占位创建及目标任务检查。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = RuntimeException.class)
    public ChapterEntity lockChapter(Long chapterId) {
        ChapterEntity chapter = chapterMapper.selectByIdForUpdate(chapterId);
        if (chapter == null) {
            throw conflict("章节不存在");
        }
        return chapter;
    }

    /** 在插入任务前绑定目标；候选占位与任务创建属于同一事务。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = RuntimeException.class)
    public void prepare(ChapterEntity chapter, ChapterGenerationEntity generation,
            CreateSceneGenerationRequest request) {
        if (!Set.of(MODE_REPLACE, MODE_NEW).contains(request.resultMode())
                || !SELECTION_ALL.equals(generation.getSelectionMode())
                || request.baseGenerationId() != null || Boolean.TRUE.equals(request.includeCurrentContent())) {
            throw invalid("稳定对象生成仅支持整章生成，请使用 referenceObjectId 指定基稿");
        }
        Target source = target(chapter, request.targetObjectId(), true);
        if (!Objects.equals(source.version(), request.expectedVersion())
                || !Objects.equals(source.hash(), request.expectedContentHash())) {
            throw conflict("正文对象已修改，请保存并刷新后生成");
        }
        if (MODE_REPLACE.equals(request.resultMode())) {
            requireEditable(chapter, source);
            requireIdle(chapter.getId(), source.objectId());
        }
        if (StringUtils.hasText(request.referenceObjectId())) {
            Target reference = target(chapter, request.referenceObjectId(), true);
            freezeReference(generation, reference);
        }
        Target destination = MODE_NEW.equals(request.resultMode()) ? reserve(chapter) : source;
        generation.setResultMode(request.resultMode());
        generation.setTargetObjectId(destination.objectId());
        generation.setTargetVersion(destination.version());
        generation.setTargetContentHash(destination.hash());
        generation.setTargetContentSnapshot(destination.content());
        generation.setWritebackStatus("pending");
    }

    private Target reserve(ChapterEntity chapter) {
        ChapterProseCandidateEntity candidate = new ChapterProseCandidateEntity();
        candidate.setWorkId(chapter.getWorkId());
        candidate.setChapterId(chapter.getId());
        candidate.setSourceKind("generation");
        candidate.setQualityRequestStatus("unavailable");
        candidate.setCandidateStatus("active");
        candidate.setAdoptionStatus("unadopted");
        candidate.setContent("");
        candidate.setContentHash(hash(""));
        candidate.setWordCount(0);
        candidate.setVersion(0);
        candidate.setDeleted(0);
        candidateMapper.insert(candidate);
        candidateMapper.update(null, new UpdateWrapper<ChapterProseCandidateEntity>()
                .eq("id", candidate.getId()).set("root_candidate_id", candidate.getId()));
        return new Target("candidate:" + candidate.getId(), 0, hash(""), "", candidate);
    }

    private void freezeReference(ChapterGenerationEntity generation, Target reference) {
        try {
            Map<String, Object> basis = objectMapper.readValue(generation.getBasisSnapshotJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            basis.put("currentProseBasis", Map.of("objectId", reference.objectId(),
                    "chapterVersion", reference.version(), "contentHash", reference.hash(),
                    "content", reference.content()));
            generation.setBasisSnapshotJson(objectMapper.writeValueAsString(basis));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法冻结生成参考内容", exception);
        }
    }

    private void requireIdle(Long chapterId, String objectId) {
        Long count = generationMapper.selectCount(new LambdaQueryWrapper<ChapterGenerationEntity>()
                .eq(ChapterGenerationEntity::getChapterId, chapterId)
                .eq(ChapterGenerationEntity::getTargetObjectId, objectId)
                .eq(ChapterGenerationEntity::getDeleted, 0)
                .eq(ChapterGenerationEntity::getWritebackStatus, "pending")
                .in(ChapterGenerationEntity::getGenerationStatus, List.of("queued", "running", "preview")));
        if (count != null && count > 0) {
            throw conflict("此版本正在生成，请等待完成");
        }
    }

    private void requireEditable(ChapterEntity chapter, Target target) {
        if (target.candidate() == null && chapter.getCurrentProseRevisionId() != null) {
            throw conflict("已发布正文不能直接重新生成，请生成新候选");
        }
        if (target.candidate() != null) {
            boolean active = CANDIDATE_ACTIVE.equals(target.candidate().getCandidateStatus());
            boolean unadopted = CANDIDATE_UNADOPTED.equals(target.candidate().getAdoptionStatus());
            if (!active || !unadopted) {
                throw conflict("历史或已采纳候选不能直接重新生成，请生成新候选");
            }
        }
    }

    /** 与旧物化流程共用其 REQUIRES_NEW 事务，不将候选物化为第二个对象。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = RuntimeException.class)
    public void complete(ChapterGenerationEntity supplied) {
        ChapterEntity chapter = chapterMapper.selectByIdForUpdate(supplied.getChapterId());
        ChapterGenerationEntity generation = generationMapper.selectOne(
                new LambdaQueryWrapper<ChapterGenerationEntity>()
                        .eq(ChapterGenerationEntity::getId, supplied.getId()).last("FOR UPDATE"));
        if (generation == null || !STATUS_PENDING.equals(generation.getWritebackStatus())
                || !STATUS_PREVIEW.equals(generation.getGenerationStatus())) {
            return;
        }
        if (!StringUtils.hasText(generation.getGeneratedContent())) {
            settle(generation, "failed", "生成没有返回有效正文");
            return;
        }
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            settle(generation, "conflict", "章节已删除，生成结果未覆盖正文");
            return;
        }
        Target destination;
        try {
            destination = target(chapter, generation.getTargetObjectId(), true);
            requireEditable(chapter, destination);
        } catch (BusinessException exception) {
            settle(generation, "conflict", "目标已删除或不可编辑，生成结果未覆盖正文");
            return;
        }
        if (!Objects.equals(destination.version(), generation.getTargetVersion())
                || !Objects.equals(destination.hash(), generation.getTargetContentHash())) {
            settle(generation, "conflict", "此版本已修改，生成结果未覆盖正文");
            return;
        }
        String content = generation.getGeneratedContent();
        int changed;
        if (destination.candidate() == null) {
            changed = chapterMapper.adoptContentIfVersion(chapter.getId(), content,
                    generation.getId(), destination.version());
        } else {
            ChapterProseCandidateEntity candidate = destination.candidate();
            if (candidate.getSourceGenerationId() != null) {
                // 为旧批次补记已完成物化的对象，防止迟到的旧完成事件重新创建历史候选。
                generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                        .eq("id", candidate.getSourceGenerationId()).isNull("target_object_id")
                        .set("target_object_id", destination.objectId()).set("writeback_status", "succeeded"));
            }
            changed = candidateMapper.update(null, new UpdateWrapper<ChapterProseCandidateEntity>()
                    .eq("id", candidate.getId()).eq("chapter_id", chapter.getId()).eq("deleted", 0)
                    .eq("version", destination.version()).eq("content_hash", destination.hash())
                    .eq("candidate_status", "active").eq("adoption_status", "unadopted")
                    .set("content", content).set("content_hash", hash(content))
                    .set("word_count", content.codePointCount(0, content.length()))
                    .set("source_kind", "generation").set("source_generation_id", generation.getId())
                    .set("source_bounded_revision_id", null).set("quality_generation_id", generation.getId())
                    .set("quality_request_status", "pending")
                    .set("version", destination.version() + 1).set("gmt_modified", LocalDateTime.now()));
        }
        settle(generation, changed == 1 ? "succeeded" : "conflict",
                changed == 1 ? null : "此版本已修改，生成结果未覆盖正文");
    }

    public boolean canEvaluate(Long generationId) {
        ChapterGenerationEntity generation = generationMapper.selectById(generationId);
        return generation != null && (generation.getTargetObjectId() == null
                || "succeeded".equals(generation.getWritebackStatus()));
    }

    private void settle(ChapterGenerationEntity generation, String status, String message) {
        int changed = generationMapper.update(null, new UpdateWrapper<ChapterGenerationEntity>()
                .eq("id", generation.getId()).eq("writeback_status", "pending")
                .set("writeback_status", status).set("writeback_message", message)
                .setSql("version = version + 1").set("gmt_modified", LocalDateTime.now()));
        if (changed != 1) {
            throw conflict("生成结果写回状态已变化");
        }
    }

    /** 返回对象级最新任务，而非章节最新批次；未成功写回的旧内容也不会被误标为新稿。 */
    public List<GenerationTaskSummary> tasks(Long chapterId) {
        List<ChapterGenerationEntity> generations = generationMapper.selectList(
                new LambdaQueryWrapper<ChapterGenerationEntity>()
                        .eq(ChapterGenerationEntity::getChapterId, chapterId)
                        .eq(ChapterGenerationEntity::getDeleted, 0)
                        .isNotNull(ChapterGenerationEntity::getTargetObjectId)
                        .orderByDesc(ChapterGenerationEntity::getId));
        Map<String, GenerationTaskSummary> latest = new LinkedHashMap<>();
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        Map<String, String> savedHashes = new LinkedHashMap<>();
        if (chapter != null) {
            savedHashes.put("formal:" + chapterId, hash(Objects.requireNonNullElse(chapter.getContent(), "")));
        }
        candidateMapper.selectList(new LambdaQueryWrapper<ChapterProseCandidateEntity>()
                        .eq(ChapterProseCandidateEntity::getChapterId, chapterId)
                        .eq(ChapterProseCandidateEntity::getDeleted, 0))
                .forEach(candidate -> savedHashes.put("candidate:" + candidate.getId(),
                        hash(Objects.requireNonNullElse(candidate.getContent(), ""))));
        for (ChapterGenerationEntity generation : generations) {
            if (latest.containsKey(generation.getTargetObjectId())) {
                continue;
            }
            String status = taskStatus(generation);
            boolean preserved = false;
            if (chapter != null && Set.of("failed", "canceled", "conflict").contains(status)
                    && StringUtils.hasText(generation.getTargetContentSnapshot())) {
                preserved = Objects.equals(savedHashes.get(generation.getTargetObjectId()),
                        generation.getTargetContentHash());
            }
            String message = generation.getWritebackMessage();
            if (message == null && STATUS_FAILED.equals(status)) {
                message = "生成未完成，请重新生成";
            }
            if (message == null && STATUS_CANCELED.equals(status)) {
                message = "生成已取消";
            }
            latest.put(generation.getTargetObjectId(), new GenerationTaskSummary(
                    generation.getTargetObjectId(), generation.getId(), status, preserved, message));
        }
        return List.copyOf(latest.values());
    }

    private String taskStatus(ChapterGenerationEntity generation) {
        if (Set.of(STATUS_SUCCEEDED, STATUS_CONFLICT, STATUS_FAILED)
                .contains(Objects.requireNonNullElse(generation.getWritebackStatus(), ""))) {
            return generation.getWritebackStatus();
        }
        return switch (Objects.requireNonNullElse(generation.getGenerationStatus(), "")) {
            case "failed", "canceled", "queued" -> generation.getGenerationStatus();
            case "rejected", "superseded", "accepted" -> "canceled";
            default -> "running";
        };
    }

    private Target target(ChapterEntity chapter, String objectId, boolean lock) {
        if ((FORMAL_PREFIX + chapter.getId()).equals(objectId)) {
            String content = Objects.requireNonNullElse(chapter.getContent(), "");
            return new Target(objectId, chapter.getVersion(), hash(content), content, null);
        }
        Long id;
        try {
            if (objectId == null || !objectId.startsWith(CANDIDATE_PREFIX)) {
                throw new NumberFormatException();
            }
            id = Long.valueOf(objectId.substring(CANDIDATE_PREFIX.length()));
        } catch (NumberFormatException exception) {
            throw invalid("正文对象 ID 不合法");
        }
        ChapterProseCandidateEntity candidate = lock
                ? candidateMapper.selectByIdForUpdate(chapter.getId(), id) : candidateMapper.selectById(id);
        if (candidate == null || Integer.valueOf(1).equals(candidate.getDeleted())
                || !chapter.getId().equals(candidate.getChapterId())) {
            throw invalid("正文候选不属于当前章节或已删除");
        }
        String content = Objects.requireNonNullElse(candidate.getContent(), "");
        return new Target(objectId, candidate.getVersion(), hash(content), content, candidate);
    }

    private String hash(String content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.GENERATION_STATUS_CONFLICT, message);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.GENERATION_SELECTION_INVALID, message);
    }

    private record Target(String objectId, Integer version, String hash, String content,
            ChapterProseCandidateEntity candidate) {
    }
}
