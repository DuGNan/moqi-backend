package com.dugnan.moqi.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.CreateOutlineCandidateRequest;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateCreated;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.SceneRevisionOutlineCandidateCommand;
import com.dugnan.moqi.chapter.service.OutlineCandidateService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.CloneScenePlanCandidateRequest;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.CreateOutlineRevisionCandidateRequest;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.OutlineRevisionCandidateCreated;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.ScenePlanChange;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.ScenePlanDiff;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.ScenePlanRevisionDraft;
import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;
import com.dugnan.moqi.planning.entity.ScenePlanConsistencyReportEntity;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanConsistencyReportMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;

/**
 * @author dgn
 * @date 2026-08-12
 * @description 以确定性场景差异连接修订草稿和现有章纲候选工作流。
 */
@Service
public class SceneOutlineRevisionBridgeServiceImpl implements SceneOutlineRevisionBridgeService {
    private static final String READY = "ready";
    private static final String PUBLISHED = "published";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final ChapterMapper chapterMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final ChapterPlanVersionMapper planMapper;
    private final ScenePlanVersionMapper sceneMapper;
    private final ScenePlanConsistencyReportMapper reportMapper;
    private final OutlineCandidateService outlineCandidateService;
    private final ObjectMapper objectMapper;

    public SceneOutlineRevisionBridgeServiceImpl(
            ChapterMapper chapterMapper,
            ChapterOutlineQueryMapper outlineMapper,
            ChapterPlanVersionMapper planMapper,
            ScenePlanVersionMapper sceneMapper,
            ScenePlanConsistencyReportMapper reportMapper,
            OutlineCandidateService outlineCandidateService,
            ObjectMapper objectMapper) {
        this.chapterMapper = chapterMapper;
        this.outlineMapper = outlineMapper;
        this.planMapper = planMapper;
        this.sceneMapper = sceneMapper;
        this.reportMapper = reportMapper;
        this.outlineCandidateService = outlineCandidateService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ScenePlanRevisionDraft cloneFromCurrent(Long chapterId, CloneScenePlanCandidateRequest request) {
        ChapterEntity chapter = requireChapterForUpdate(chapterId);
        String idempotencyKey = idempotencyKey(request == null ? null : request.idempotencyKey());
        ChapterPlanVersionEntity existing = planMapper.selectOne(
                new LambdaQueryWrapper<ChapterPlanVersionEntity>()
                        .eq(ChapterPlanVersionEntity::getChapterId, chapterId)
                        .eq(ChapterPlanVersionEntity::getRevisionIdempotencyKey, idempotencyKey)
                        .eq(ChapterPlanVersionEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        if (existing != null) {
            if (request == null || !Objects.equals(request.sourcePlanId(), existing.getSourceScenePlanId())
                    || !Objects.equals(request.baseOutlineRevision(), existing.getOutlineRevision())) {
                throw idempotencyConflict("幂等键已用于不同的场景修订来源");
            }
            return draft(existing);
        }
        ChapterPlanVersionEntity source = requireCurrentPublished(chapterId,
                request == null ? null : request.sourcePlanId());
        ChapterOutlineEntity outline = requireOutline(chapterId, request == null ? null : request.baseOutlineRevision());
        if (!outline.getId().equals(source.getOutlineId())
                || !outline.getRevision().equals(source.getOutlineRevision())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_OUTLINE_STALE, "已发布场景规划绑定的章纲不是当前版本");
        }

        ChapterPlanVersionEntity candidate = new ChapterPlanVersionEntity();
        candidate.setWorkId(chapter.getWorkId());
        candidate.setChapterId(chapterId);
        candidate.setPlanNo(nextPlanNo(chapterId));
        candidate.setNarrativePlanId(source.getNarrativePlanId());
        candidate.setNarrativePlanNo(source.getNarrativePlanNo());
        candidate.setOutlineId(source.getOutlineId());
        candidate.setOutlineRevision(source.getOutlineRevision());
        candidate.setOutlineContentSchemaVersion(source.getOutlineContentSchemaVersion());
        candidate.setOutlineMigrationReviewStatus(source.getOutlineMigrationReviewStatus());
        candidate.setPlanStatus(READY);
        candidate.setContentJson(source.getContentJson());
        candidate.setSourceType("manual_revision");
        candidate.setSourceScenePlanId(source.getId());
        candidate.setSourceScenePlanVersion(source.getVersion());
        candidate.setRevisionIdempotencyKey(idempotencyKey);
        candidate.setCreatedBy("local-user");
        candidate.setSourceSnapshotId(source.getSourceSnapshotId());
        candidate.setValidityStatus("current");
        candidate.setDeleted(0);
        candidate.setVersion(0);
        planMapper.insert(candidate);

        for (ScenePlanVersionEntity sourceScene : activeScenes(source.getId())) {
            ScenePlanVersionEntity cloned = new ScenePlanVersionEntity();
            cloned.setChapterPlanVersionId(candidate.getId());
            cloned.setSceneKey(sourceScene.getSceneKey());
            cloned.setSequenceNo(sourceScene.getSequenceNo());
            cloned.setContentSchemaVersion(sourceScene.getContentSchemaVersion());
            cloned.setContentJson(sourceScene.getContentJson());
            cloned.setDeleted(0);
            cloned.setVersion(0);
            sceneMapper.insert(cloned);
        }
        return draft(candidate);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public OutlineRevisionCandidateCreated createOutlineCandidate(
            Long chapterId,
            Long planId,
            CreateOutlineRevisionCandidateRequest request) {
        requireChapterForUpdate(chapterId);
        String idempotencyKey = idempotencyKey(request == null ? null : request.idempotencyKey());
        ChapterPlanVersionEntity candidate = requireRevisionCandidate(chapterId, planId);
        if (request == null || request.baseVersion() == null
                || !request.baseVersion().equals(candidate.getVersion())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_CONFLICT, "场景修订草稿版本已变化");
        }
        ChapterOutlineEntity outline = requireOutline(chapterId, request.baseOutlineRevision());
        if (!Objects.equals(candidate.getOutlineId(), outline.getId())
                || !Objects.equals(candidate.getOutlineRevision(), outline.getRevision())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_OUTLINE_STALE, "场景修订草稿绑定的章纲已更新");
        }
        ScenePlanConsistencyReportEntity report = requireCurrentReport(
                chapterId, candidate, request.consistencyReportId());
        ChapterPlanVersionEntity source = requireCurrentPublished(chapterId, candidate.getSourceScenePlanId());
        if (!Objects.equals(source.getVersion(), candidate.getSourceScenePlanVersion())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_SOURCE_STALE, "已发布场景规划来源版本已变化");
        }

        ScenePlanDiff diff = diff(source, candidate);
        if (diff.changes().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "场景修订草稿没有可生成章纲候选的变化");
        }
        String diffJson = json(diff);
        String instruction = "根据场景规划修订差异调整章纲；共 " + diff.changes().size()
                + " 项场景变化。仅生成候选，不确认章纲或发布场景规划。";
        CreateOutlineCandidateRequest outlineRequest = new CreateOutlineCandidateRequest(
                request.conversationId(), request.confirmedBriefId(), request.baseOutlineRevision(),
                instruction, "adjustment", idempotencyKey);
        OutlineCandidateCreated created = outlineCandidateService.createFromSceneRevision(chapterId,
                new SceneRevisionOutlineCandidateCommand(outlineRequest, candidate.getId(), candidate.getVersion(),
                        report.getId(), diffJson));
        return new OutlineRevisionCandidateCreated(created, diff);
    }

    private ChapterEntity requireChapterForUpdate(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectByIdForUpdate(chapterId);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private ChapterPlanVersionEntity requireCurrentPublished(Long chapterId, Long planId) {
        ChapterPlanVersionEntity source = planId == null ? null : planMapper.selectById(planId);
        if (source == null || Integer.valueOf(1).equals(source.getDeleted())
                || !chapterId.equals(source.getChapterId()) || !PUBLISHED.equals(source.getPlanStatus())
                || !Integer.valueOf(1).equals(source.getCurrentMarker())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_SOURCE_STALE, "来源必须是同章当前已发布场景规划");
        }
        return source;
    }

    private ChapterPlanVersionEntity requireRevisionCandidate(Long chapterId, Long planId) {
        ChapterPlanVersionEntity candidate = planId == null ? null : planMapper.selectById(planId);
        if (candidate == null || Integer.valueOf(1).equals(candidate.getDeleted())
                || !chapterId.equals(candidate.getChapterId()) || !READY.equals(candidate.getPlanStatus())
                || candidate.getSourceScenePlanId() == null) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_NOT_FOUND, "场景修订草稿不存在或状态不可用");
        }
        return candidate;
    }

    private ChapterOutlineEntity requireOutline(Long chapterId, Integer revision) {
        ChapterOutlineEntity outline = outlineMapper.findLatest(chapterId);
        if (outline == null || revision == null || !revision.equals(outline.getRevision())) {
            throw new BusinessException(ErrorCode.OUTLINE_REVISION_CONFLICT, "章纲已更新，请刷新后重试");
        }
        return outline;
    }

    private ScenePlanConsistencyReportEntity requireCurrentReport(
            Long chapterId,
            ChapterPlanVersionEntity candidate,
            Long reportId) {
        ScenePlanConsistencyReportEntity report = reportId == null ? null : reportMapper.selectById(reportId);
        if (report == null || Integer.valueOf(1).equals(report.getDeleted())
                || !chapterId.equals(report.getChapterId())
                || !candidate.getId().equals(report.getChapterPlanVersionId())
                || !candidate.getVersion().equals(report.getPlanVersion())
                || !READY.equals(report.getReportStatus())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_CONSISTENCY_CONFLICT,
                    "一致性报告不属于当前场景修订版本或尚未完成");
        }
        return report;
    }

    private List<ScenePlanVersionEntity> activeScenes(Long planId) {
        return sceneMapper.findAllByPlanId(planId).stream()
                .filter(scene -> !Integer.valueOf(1).equals(scene.getDeleted()))
                .sorted(Comparator.comparing(ScenePlanVersionEntity::getSequenceNo))
                .toList();
    }

    private ScenePlanDiff diff(ChapterPlanVersionEntity source, ChapterPlanVersionEntity candidate) {
        Map<String, ScenePlanVersionEntity> before = byKey(activeScenes(source.getId()));
        Map<String, ScenePlanVersionEntity> after = byKey(activeScenes(candidate.getId()));
        Set<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        List<ScenePlanChange> changes = new ArrayList<>();
        for (String key : keys) {
            ScenePlanVersionEntity left = before.get(key);
            ScenePlanVersionEntity right = after.get(key);
            if (left == null) {
                changes.add(new ScenePlanChange(key, "added", null, right.getSequenceNo(), List.of()));
            } else if (right == null) {
                changes.add(new ScenePlanChange(key, "removed", left.getSequenceNo(), null, List.of()));
            } else {
                List<String> fields = changedFields(left.getContentJson(), right.getContentJson());
                boolean moved = !Objects.equals(left.getSequenceNo(), right.getSequenceNo());
                if (moved || !fields.isEmpty()) {
                    changes.add(new ScenePlanChange(key, moved && fields.isEmpty() ? "moved" : "modified",
                            left.getSequenceNo(), right.getSequenceNo(), fields));
                }
            }
        }
        return new ScenePlanDiff(source.getId(), source.getVersion(), candidate.getId(), candidate.getVersion(), changes);
    }

    private Map<String, ScenePlanVersionEntity> byKey(List<ScenePlanVersionEntity> scenes) {
        Map<String, ScenePlanVersionEntity> result = new LinkedHashMap<>();
        scenes.forEach(scene -> result.put(scene.getSceneKey(), scene));
        return result;
    }

    private List<String> changedFields(String beforeJson, String afterJson) {
        JsonNode before = readTree(beforeJson);
        JsonNode after = readTree(afterJson);
        Set<String> fields = new TreeSet<>();
        before.fieldNames().forEachRemaining(fields::add);
        after.fieldNames().forEachRemaining(fields::add);
        return fields.stream().filter(field -> !Objects.equals(before.get(field), after.get(field))).toList();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_INVALID, "场景规划内容无法读取", exception);
        }
    }

    private int nextPlanNo(Long chapterId) {
        return planMapper.selectCount(new LambdaQueryWrapper<ChapterPlanVersionEntity>()
                .eq(ChapterPlanVersionEntity::getChapterId, chapterId)).intValue() + 1;
    }

    private String idempotencyKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized) || normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "idempotencyKey 不能为空且长度不能超过 128 字符");
        }
        return normalized;
    }

    private ScenePlanRevisionDraft draft(ChapterPlanVersionEntity candidate) {
        return new ScenePlanRevisionDraft(candidate.getId(), candidate.getSourceScenePlanId(),
                candidate.getSourceScenePlanVersion(), candidate.getOutlineRevision(), candidate.getPlanStatus(),
                candidate.getVersion(), candidate.getRevisionIdempotencyKey());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "场景差异无法序列化", exception);
        }
    }

    private BusinessException idempotencyConflict(String message) {
        return new BusinessException(ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT, message);
    }
}
