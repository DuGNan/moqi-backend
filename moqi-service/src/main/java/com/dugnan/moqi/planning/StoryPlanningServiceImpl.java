package com.dugnan.moqi.planning;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.agent.AgentRuntime;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.StartAgentRunCommand;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanContent;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PlanningModels.CreateNarrativePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.CreateScenePlanCandidateRequest;
import com.dugnan.moqi.planning.PlanningModels.NarrativePlanContent;
import com.dugnan.moqi.planning.PlanningModels.NarrativePlanView;
import com.dugnan.moqi.planning.PlanningModels.PublishNarrativePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.PublishScenePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;
import com.dugnan.moqi.planning.PlanningModels.UpdateNarrativePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.UpdateScenePlanCandidateRequest;
import com.dugnan.moqi.planning.entity.ChapterPlanVersionEntity;
import com.dugnan.moqi.planning.entity.ScenePlanVersionEntity;
import com.dugnan.moqi.planning.entity.WorkNarrativePlanVersionEntity;
import com.dugnan.moqi.planning.mapper.ChapterPlanVersionMapper;
import com.dugnan.moqi.planning.mapper.ScenePlanVersionMapper;
import com.dugnan.moqi.planning.mapper.WorkNarrativePlanVersionMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 实现三级故事规划的版本、候选和发布事务。
 */
@Service
public class StoryPlanningServiceImpl implements StoryPlanningService {
    private static final String LOCAL_USER = "local-user";
    private static final String DRAFT = "draft";
    private static final String PUBLISHED = "published";
    private static final String SUPERSEDED = "superseded";
    private static final String QUEUED = "queued";
    private static final String READY = "ready";
    private static final String ABANDONED = "abandoned";
    private static final String TASK_TYPE = "scene_plan_generation";

    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final WorkNarrativePlanVersionMapper narrativeMapper;
    private final ChapterPlanVersionMapper chapterPlanMapper;
    private final ScenePlanVersionMapper sceneMapper;
    private final AiTaskMapper taskMapper;
    private final AgentRuntime agentRuntime;
    private final PlanningContentCodec codec;
    private final ObjectMapper objectMapper;

    public StoryPlanningServiceImpl(WorkMapper workMapper, ChapterMapper chapterMapper,
            ChapterOutlineQueryMapper outlineMapper, WorkNarrativePlanVersionMapper narrativeMapper,
            ChapterPlanVersionMapper chapterPlanMapper, ScenePlanVersionMapper sceneMapper, AiTaskMapper taskMapper,
            AgentRuntime agentRuntime, PlanningContentCodec codec, ObjectMapper objectMapper) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.outlineMapper = outlineMapper;
        this.narrativeMapper = narrativeMapper;
        this.chapterPlanMapper = chapterPlanMapper;
        this.sceneMapper = sceneMapper;
        this.taskMapper = taskMapper;
        this.agentRuntime = agentRuntime;
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public NarrativePlanView createNarrativePlan(Long workId, CreateNarrativePlanRequest request) {
        requireWork(workId);
        NarrativePlanContent content = codec.narrative(request == null ? null : request.content());
        WorkNarrativePlanVersionEntity entity = new WorkNarrativePlanVersionEntity();
        entity.setWorkId(workId);
        entity.setPlanNo(nextNarrativePlanNo(workId));
        entity.setPlanStatus(DRAFT);
        entity.setContentJson(json(content));
        entity.setSourceType("manual");
        entity.setCreatedBy(LOCAL_USER);
        entity.setDeleted(0);
        entity.setVersion(0);
        narrativeMapper.insert(entity);
        return narrativeView(entity);
    }

    @Override
    public NarrativePlanView getNarrativePlan(Long workId, Long planId) {
        requireWork(workId);
        return narrativeView(requireNarrative(workId, planId));
    }

    @Override
    public NarrativePlanView getCurrentNarrativePlan(Long workId) {
        requireWork(workId);
        WorkNarrativePlanVersionEntity entity = currentNarrative(workId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NARRATIVE_PLAN_REQUIRED, "请先发布作品叙事规划");
        }
        return narrativeView(entity);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public NarrativePlanView updateNarrativePlan(Long workId, Long planId, UpdateNarrativePlanRequest request) {
        WorkNarrativePlanVersionEntity entity = requireNarrative(workId, planId);
        if (!DRAFT.equals(entity.getPlanStatus()) || request == null || request.baseVersion() == null) {
            throw narrativeConflict("仅草稿可编辑，且必须提交 baseVersion");
        }
        if (!request.baseVersion().equals(entity.getVersion())) {
            throw narrativeConflict("作品叙事规划已被更新，请刷新后重试");
        }
        NarrativePlanContent content = codec.narrative(request.content());
        int changed = narrativeMapper.update(null, new UpdateWrapper<WorkNarrativePlanVersionEntity>()
                .eq("id", entity.getId()).eq("work_id", workId).eq("version", entity.getVersion()).eq("plan_status", DRAFT)
                .set("content_json", json(content)).setSql("version = version + 1"));
        if (changed != 1) {
            throw narrativeConflict("作品叙事规划已被更新，请刷新后重试");
        }
        return narrativeView(requireNarrative(workId, planId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public NarrativePlanView publishNarrativePlan(Long workId, Long planId, PublishNarrativePlanRequest request) {
        WorkNarrativePlanVersionEntity entity = requireNarrative(workId, planId);
        if (PUBLISHED.equals(entity.getPlanStatus())) {
            return narrativeView(entity);
        }
        if (!DRAFT.equals(entity.getPlanStatus()) || request == null || request.baseVersion() == null
                || !request.baseVersion().equals(entity.getVersion())) {
            throw narrativeConflict("叙事规划状态或版本已变化");
        }
        narrativeMapper.update(null, new UpdateWrapper<WorkNarrativePlanVersionEntity>().eq("work_id", workId)
                .eq("current_marker", 1).eq("deleted", 0).set("plan_status", SUPERSEDED).set("current_marker", null)
                .setSql("version = version + 1"));
        int changed = narrativeMapper.update(null, new UpdateWrapper<WorkNarrativePlanVersionEntity>().eq("id", planId)
                .eq("version", entity.getVersion()).eq("plan_status", DRAFT).set("plan_status", PUBLISHED)
                .set("published_by", LOCAL_USER).set("current_marker", 1).setSql("version = version + 1"));
        if (changed != 1) {
            throw narrativeConflict("叙事规划发布冲突");
        }
        return narrativeView(requireNarrative(workId, planId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ChapterPlanView createCandidate(Long chapterId, CreateScenePlanCandidateRequest request) {
        ChapterEntity chapter = requireChapter(chapterId);
        WorkNarrativePlanVersionEntity narrative = currentNarrative(chapter.getWorkId());
        if (narrative == null) {
            throw new BusinessException(ErrorCode.NARRATIVE_PLAN_REQUIRED, "请先发布作品叙事规划");
        }
        ChapterOutlineEntity outline = outlineMapper.findLatest(chapterId);
        if (outline == null || request == null || request.baseOutlineRevision() == null
                || !request.baseOutlineRevision().equals(outline.getRevision())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_OUTLINE_STALE, "章节大纲已更新，请刷新后重试");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "idempotencyKey 不能为空");
        }
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(TASK_TYPE);
        task.setTaskStatus(QUEUED);
        task.setWorkId(chapter.getWorkId());
        task.setChapterId(chapterId);
        task.setTaskInputJson(json(java.util.Map.of("chapterId", chapterId, "outlineId", outline.getId(),
                "outlineRevision", outline.getRevision())));
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);
        ChapterPlanVersionEntity candidate = new ChapterPlanVersionEntity();
        candidate.setWorkId(chapter.getWorkId());
        candidate.setChapterId(chapterId);
        candidate.setPlanNo(nextChapterPlanNo(chapterId));
        candidate.setNarrativePlanId(narrative.getId());
        candidate.setNarrativePlanNo(narrative.getPlanNo());
        candidate.setOutlineId(outline.getId());
        candidate.setOutlineRevision(outline.getRevision());
        candidate.setAiTaskId(task.getId());
        candidate.setPlanStatus(QUEUED);
        candidate.setSourceType("model");
        candidate.setCreatedBy(LOCAL_USER);
        candidate.setDeleted(0);
        candidate.setVersion(0);
        chapterPlanMapper.insert(candidate);
        task.setResultScenePlanVersionId(candidate.getId());
        taskMapper.updateById(task);
        var run = agentRuntime.start(new StartAgentRunCommand(LOCAL_USER, chapter.getWorkId(), chapterId,
                ScenePlanWorkflowDefinition.WORKFLOW_TYPE, request.idempotencyKey(), (long) outline.getRevision(),
                java.util.Map.of("candidateId", candidate.getId()), task.getId()));
        candidate.setAgentRunId(run.runId());
        chapterPlanMapper.updateById(candidate);
        return chapterPlanView(candidate);
    }

    @Override
    public ChapterPlanView getCandidate(Long chapterId, Long planId) {
        requireChapter(chapterId);
        return chapterPlanView(requireChapterPlan(chapterId, planId));
    }

    @Override
    public ChapterPlanView getLatestCandidate(Long chapterId) {
        requireChapter(chapterId);
        ChapterPlanVersionEntity entity = chapterPlanMapper.selectOne(new LambdaQueryWrapper<ChapterPlanVersionEntity>()
                .eq(ChapterPlanVersionEntity::getChapterId, chapterId).eq(ChapterPlanVersionEntity::getDeleted, 0)
                .in(ChapterPlanVersionEntity::getPlanStatus, QUEUED, READY).orderByDesc(ChapterPlanVersionEntity::getId).last("LIMIT 1"));
        return entity == null ? null : chapterPlanView(entity);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ChapterPlanView updateCandidate(Long chapterId, Long planId, UpdateScenePlanCandidateRequest request) {
        ChapterPlanVersionEntity entity = requireChapterPlan(chapterId, planId);
        if (!READY.equals(entity.getPlanStatus()) || request == null || request.baseVersion() == null
                || !request.baseVersion().equals(entity.getVersion())) {
            throw sceneConflict("候选状态或版本已变化");
        }
        List<ScenePlanContent> scenes = codec.scenes(request.scenes());
        ChapterPlanContent content = request.content();
        if (content == null || blank(content.chapterGoal()) || blank(content.chapterConflict()) || blank(content.expectedOutcome())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_INVALID, "章节规划内容不完整");
        }
        int changed = chapterPlanMapper.update(null, new UpdateWrapper<ChapterPlanVersionEntity>().eq("id", planId)
                .eq("version", entity.getVersion()).eq("plan_status", READY).set("content_json", json(content))
                .setSql("version = version + 1"));
        if (changed != 1) {
            throw sceneConflict("候选已被更新，请刷新后重试");
        }
        sceneMapper.delete(new LambdaQueryWrapper<ScenePlanVersionEntity>().eq(ScenePlanVersionEntity::getChapterPlanVersionId, planId));
        saveScenes(planId, scenes);
        return chapterPlanView(requireChapterPlan(chapterId, planId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ChapterPlanView abandonCandidate(Long chapterId, Long planId) {
        ChapterPlanVersionEntity entity = requireChapterPlan(chapterId, planId);
        if (ABANDONED.equals(entity.getPlanStatus())) {
            return chapterPlanView(entity);
        }
        if (!READY.equals(entity.getPlanStatus()) && !QUEUED.equals(entity.getPlanStatus())) {
            throw sceneConflict("仅候选状态可放弃");
        }
        chapterPlanMapper.update(null, new UpdateWrapper<ChapterPlanVersionEntity>().eq("id", planId).eq("version", entity.getVersion())
                .set("plan_status", ABANDONED).setSql("version = version + 1"));
        if (entity.getAgentRunId() != null) {
            agentRuntime.cancel(entity.getAgentRunId());
        }
        return chapterPlanView(requireChapterPlan(chapterId, planId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ChapterPlanView publishCandidate(Long chapterId, Long planId, PublishScenePlanRequest request) {
        ChapterPlanVersionEntity entity = requireChapterPlan(chapterId, planId);
        if (PUBLISHED.equals(entity.getPlanStatus())) {
            return chapterPlanView(entity);
        }
        if (!READY.equals(entity.getPlanStatus()) || request == null || request.baseVersion() == null
                || !request.baseVersion().equals(entity.getVersion())) {
            throw sceneConflict("候选状态或版本已变化");
        }
        assertSourcesCurrent(entity);
        chapterPlanMapper.update(null, new UpdateWrapper<ChapterPlanVersionEntity>().eq("chapter_id", chapterId)
                .eq("current_marker", 1).eq("deleted", 0).set("plan_status", SUPERSEDED).set("current_marker", null)
                .setSql("version = version + 1"));
        int changed = chapterPlanMapper.update(null, new UpdateWrapper<ChapterPlanVersionEntity>().eq("id", planId)
                .eq("version", entity.getVersion()).eq("plan_status", READY).set("plan_status", PUBLISHED)
                .set("published_by", LOCAL_USER).set("current_marker", 1).setSql("version = version + 1"));
        if (changed != 1) {
            throw sceneConflict("场景规划发布冲突");
        }
        if (entity.getAgentRunId() != null) {
            agentRuntime.cancel(entity.getAgentRunId());
        }
        return chapterPlanView(requireChapterPlan(chapterId, planId));
    }

    @Override
    public ChapterPlanView loadCurrent(Long chapterId) {
        requireChapter(chapterId);
        ChapterPlanVersionEntity entity = chapterPlanMapper.selectOne(new LambdaQueryWrapper<ChapterPlanVersionEntity>()
                .eq(ChapterPlanVersionEntity::getChapterId, chapterId).eq(ChapterPlanVersionEntity::getCurrentMarker, 1)
                .eq(ChapterPlanVersionEntity::getDeleted, 0));
        if (entity == null) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_NOT_FOUND, "章节尚未发布场景规划");
        }
        return chapterPlanView(entity);
    }

    @Override
    public ChapterPlanView loadPublished(Long chapterId, Integer planNo) {
        requireChapter(chapterId);
        ChapterPlanVersionEntity entity = chapterPlanMapper.selectOne(new LambdaQueryWrapper<ChapterPlanVersionEntity>()
                .eq(ChapterPlanVersionEntity::getChapterId, chapterId).eq(ChapterPlanVersionEntity::getPlanNo, planNo)
                .eq(ChapterPlanVersionEntity::getPlanStatus, PUBLISHED).eq(ChapterPlanVersionEntity::getDeleted, 0));
        if (entity == null) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_NOT_FOUND, "已发布场景规划不存在");
        }
        return chapterPlanView(entity);
    }

    private void saveScenes(Long planId, List<ScenePlanContent> scenes) {
        for (ScenePlanContent scene : scenes) {
            ScenePlanVersionEntity entity = new ScenePlanVersionEntity();
            entity.setChapterPlanVersionId(planId);
            entity.setSceneKey(scene.sceneKey());
            entity.setSequenceNo(scene.sequence());
            entity.setContentJson(json(scene));
            entity.setDeleted(0);
            entity.setVersion(0);
            sceneMapper.insert(entity);
        }
    }

    private void assertSourcesCurrent(ChapterPlanVersionEntity entity) {
        WorkNarrativePlanVersionEntity narrative = currentNarrative(entity.getWorkId());
        ChapterOutlineEntity outline = outlineMapper.findLatest(entity.getChapterId());
        if (narrative == null || !entity.getNarrativePlanId().equals(narrative.getId()) || outline == null
                || !entity.getOutlineId().equals(outline.getId()) || !entity.getOutlineRevision().equals(outline.getRevision())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_OUTLINE_STALE, "作品规划或章节大纲已更新，请重新生成候选");
        }
    }

    private NarrativePlanView narrativeView(WorkNarrativePlanVersionEntity entity) {
        return new NarrativePlanView(entity.getId(), entity.getWorkId(), entity.getPlanNo(), entity.getPlanStatus(),
                read(entity.getContentJson(), NarrativePlanContent.class), entity.getVersion(), entity.getGmtCreate(), entity.getGmtModified());
    }

    private ChapterPlanView chapterPlanView(ChapterPlanVersionEntity entity) {
        List<ScenePlanView> scenes = sceneMapper.selectList(new LambdaQueryWrapper<ScenePlanVersionEntity>()
                .eq(ScenePlanVersionEntity::getChapterPlanVersionId, entity.getId()).eq(ScenePlanVersionEntity::getDeleted, 0)
                .orderByAsc(ScenePlanVersionEntity::getSequenceNo)).stream()
                .map(scene -> new ScenePlanView(scene.getSceneKey(), scene.getSequenceNo(), read(scene.getContentJson(), ScenePlanContent.class))).toList();
        return new ChapterPlanView(entity.getId(), entity.getChapterId(), entity.getPlanNo(), entity.getPlanStatus(),
                entity.getNarrativePlanId(), entity.getNarrativePlanNo(), entity.getOutlineId(), entity.getOutlineRevision(),
                entity.getAiTaskId(), entity.getAgentRunId(), readOrNull(entity.getContentJson(), ChapterPlanContent.class), scenes,
                entity.getVersion(), entity.getGmtCreate(), entity.getGmtModified());
    }

    private WorkNarrativePlanVersionEntity currentNarrative(Long workId) {
        return narrativeMapper.selectOne(new LambdaQueryWrapper<WorkNarrativePlanVersionEntity>().eq(WorkNarrativePlanVersionEntity::getWorkId, workId)
                .eq(WorkNarrativePlanVersionEntity::getCurrentMarker, 1).eq(WorkNarrativePlanVersionEntity::getDeleted, 0));
    }

    private WorkNarrativePlanVersionEntity requireNarrative(Long workId, Long planId) {
        requireWork(workId);
        WorkNarrativePlanVersionEntity entity = planId == null ? null : narrativeMapper.selectById(planId);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted()) || !workId.equals(entity.getWorkId())) {
            throw new BusinessException(ErrorCode.NARRATIVE_PLAN_NOT_FOUND, "作品叙事规划不存在");
        }
        return entity;
    }

    private ChapterPlanVersionEntity requireChapterPlan(Long chapterId, Long planId) {
        ChapterPlanVersionEntity entity = planId == null ? null : chapterPlanMapper.selectById(planId);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted()) || !chapterId.equals(entity.getChapterId())) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_NOT_FOUND, "章节场景规划不存在");
        }
        return entity;
    }

    private WorkEntity requireWork(Long workId) {
        WorkEntity entity = workId == null ? null : workMapper.selectById(workId);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return entity;
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity entity = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        requireWork(entity.getWorkId());
        return entity;
    }

    private int nextNarrativePlanNo(Long workId) {
        Long count = narrativeMapper.selectCount(new LambdaQueryWrapper<WorkNarrativePlanVersionEntity>().eq(WorkNarrativePlanVersionEntity::getWorkId, workId));
        return count.intValue() + 1;
    }

    private int nextChapterPlanNo(Long chapterId) {
        Long count = chapterPlanMapper.selectCount(new LambdaQueryWrapper<ChapterPlanVersionEntity>().eq(ChapterPlanVersionEntity::getChapterId, chapterId));
        return count.intValue() + 1;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "规划数据无法序列化", exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        T value = readOrNull(json, type);
        if (value == null) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_INVALID, "规划数据缺失");
        }
        return value;
    }

    private <T> T readOrNull(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.SCENE_PLAN_INVALID, "规划数据无法读取", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BusinessException narrativeConflict(String message) {
        return new BusinessException(ErrorCode.NARRATIVE_PLAN_CONFLICT, message);
    }

    private BusinessException sceneConflict(String message) {
        return new BusinessException(ErrorCode.SCENE_PLAN_CONFLICT, message);
    }
}
