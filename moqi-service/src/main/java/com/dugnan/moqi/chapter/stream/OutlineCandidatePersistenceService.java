package com.dugnan.moqi.chapter.stream;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusImpactService;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConsensusImpact;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateDiff;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterOutlineCandidateEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterOutlineCandidateMapper;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContentCodec;
import com.dugnan.moqi.chapter.outline.OutlineCandidateDiffService;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 在短事务中推进候选任务状态并持久化候选结果。
 */
@Service
public class OutlineCandidatePersistenceService {

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String CANDIDATE_READY = "ready";
    private static final String CANDIDATE_FAILED = "failed";

    private final AiTaskMapper taskMapper;
    private final ChapterOutlineCandidateMapper candidateMapper;
    private final OutlineCandidateContentCodec contentCodec;
    private final OutlineCandidateDiffService diffService;
    private final ChapterConsensusImpactService impactService;
    private final ObjectMapper objectMapper;

    /**
     * 创建候选结果持久化服务。
     */
    public OutlineCandidatePersistenceService(
            AiTaskMapper taskMapper,
            ChapterOutlineCandidateMapper candidateMapper,
            OutlineCandidateContentCodec contentCodec,
            OutlineCandidateDiffService diffService,
            ChapterConsensusImpactService impactService,
            ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.candidateMapper = candidateMapper;
        this.contentCodec = contentCodec;
        this.diffService = diffService;
        this.impactService = impactService;
        this.objectMapper = objectMapper;
    }

    /**
     * 原子领取 queued 任务及其候选资源。
     *
     * @param task 待执行任务
     * @param candidate 候选资源
     * @return 是否领取成功
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public boolean claim(AiTaskEntity task, ChapterOutlineCandidateEntity candidate) {
        int taskVersion = version(task);
        int taskChanged = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", taskVersion)
                .eq("task_status", STATUS_QUEUED)
                .set("task_status", STATUS_RUNNING)
                .set("version", taskVersion + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (taskChanged != 1) {
            return false;
        }
        int candidateVersion = version(candidate);
        int candidateChanged = candidateMapper.update(null, new UpdateWrapper<ChapterOutlineCandidateEntity>()
                .eq("id", candidate.getId())
                .eq("ai_task_id", task.getId())
                .eq("deleted", 0)
                .eq("version", candidateVersion)
                .eq("candidate_status", STATUS_QUEUED)
                .set("candidate_status", STATUS_RUNNING)
                .set("version", candidateVersion + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (candidateChanged != 1) {
            throw new OutlineCandidateTaskCompletionException();
        }
        task.setTaskStatus(STATUS_RUNNING);
        task.setVersion(taskVersion + 1);
        candidate.setCandidateStatus(STATUS_RUNNING);
        candidate.setVersion(candidateVersion + 1);
        return true;
    }

    /**
     * 保存模型结果并同时完成任务。
     *
     * @param task 正在运行的任务
     * @param candidate 正在运行的候选
     * @param briefContent 指定已确认 Brief 内容
     * @param generatedContent 模型生成内容
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public void complete(
            AiTaskEntity task,
            ChapterOutlineCandidateEntity candidate,
            String briefContent,
            OutlineCandidateContent generatedContent) {
        OutlineCandidateContent content = contentCodec.normalize(generatedContent);
        OutlineCandidateDiff diff = candidate.getBaseOutlineContent() == null
                ? null : diffService.diff(contentCodec.read(candidate.getBaseOutlineContent()), content);
        ConsensusImpact impact = impactService.assess(briefContent, contentCodec.write(content));
        int candidateVersion = version(candidate);
        int candidateChanged = candidateMapper.update(null, new UpdateWrapper<ChapterOutlineCandidateEntity>()
                .eq("id", candidate.getId())
                .eq("ai_task_id", task.getId())
                .eq("deleted", 0)
                .eq("version", candidateVersion)
                .eq("candidate_status", STATUS_RUNNING)
                .set("candidate_status", CANDIDATE_READY)
                .set("candidate_content", contentCodec.write(content))
                .set("content_schema_version", OutlineCandidateContent.SCHEMA_VERSION)
                .set("migration_review_status", "not_required")
                .set("migration_reason_codes_json", null)
                .set("diff_json", write(diff))
                .set("consensus_impact_json", write(impact))
                .set("version", candidateVersion + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (candidateChanged != 1) {
            throw new OutlineCandidateTaskCompletionException();
        }
        int taskVersion = version(task);
        int taskChanged = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", taskVersion)
                .eq("task_status", STATUS_RUNNING)
                .eq("result_outline_candidate_id", candidate.getId())
                .set("task_status", STATUS_SUCCEEDED)
                .set("version", taskVersion + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (taskChanged != 1) {
            throw new OutlineCandidateTaskCompletionException();
        }
        candidate.setCandidateStatus(CANDIDATE_READY);
        candidate.setCandidateContent(contentCodec.write(content));
        candidate.setDiffJson(write(diff));
        candidate.setConsensusImpactJson(write(impact));
        candidate.setContentSchemaVersion(OutlineCandidateContent.SCHEMA_VERSION);
        candidate.setMigrationReviewStatus("not_required");
        candidate.setVersion(candidateVersion + 1);
        task.setTaskStatus(STATUS_SUCCEEDED);
        task.setVersion(taskVersion + 1);
    }

    /**
     * 将正在运行的任务和候选同时标记失败。
     *
     * @return 是否实际发生状态推进
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public boolean fail(AiTaskEntity task, ChapterOutlineCandidateEntity candidate, String errorCode, String errorMessage) {
        int taskVersion = version(task);
        int taskChanged = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", taskVersion)
                .eq("task_status", STATUS_RUNNING)
                .set("task_status", STATUS_FAILED)
                .set("error_code", errorCode)
                .set("error_message", errorMessage)
                .set("version", taskVersion + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (taskChanged != 1) {
            return false;
        }
        int candidateVersion = version(candidate);
        int candidateChanged = candidateMapper.update(null, new UpdateWrapper<ChapterOutlineCandidateEntity>()
                .eq("id", candidate.getId())
                .eq("ai_task_id", task.getId())
                .eq("deleted", 0)
                .eq("version", candidateVersion)
                .eq("candidate_status", STATUS_RUNNING)
                .set("candidate_status", CANDIDATE_FAILED)
                .set("version", candidateVersion + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (candidateChanged != 1) {
            throw new OutlineCandidateTaskCompletionException();
        }
        task.setTaskStatus(STATUS_FAILED);
        task.setVersion(taskVersion + 1);
        candidate.setCandidateStatus(CANDIDATE_FAILED);
        candidate.setVersion(candidateVersion + 1);
        return true;
    }

    /**
     * 将排队任务及其候选写为拒绝失败。
     *
     * @param task 任务
     * @param candidate 候选
     * @return 是否实际发生状态推进
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public boolean reject(AiTaskEntity task, ChapterOutlineCandidateEntity candidate) {
        int taskVersion = version(task);
        int taskChanged = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", taskVersion)
                .eq("task_status", STATUS_QUEUED)
                .set("task_status", STATUS_FAILED)
                .set("error_code", ErrorCode.INTERNAL_ERROR.name())
                .set("error_message", "候选任务排队失败，请稍后重试")
                .set("version", taskVersion + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (taskChanged != 1) {
            return false;
        }
        int candidateVersion = version(candidate);
        int candidateChanged = candidateMapper.update(null, new UpdateWrapper<ChapterOutlineCandidateEntity>()
                .eq("id", candidate.getId())
                .eq("ai_task_id", task.getId())
                .eq("deleted", 0)
                .eq("version", candidateVersion)
                .eq("candidate_status", STATUS_QUEUED)
                .set("candidate_status", CANDIDATE_FAILED)
                .set("version", candidateVersion + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (candidateChanged != 1) {
            throw new OutlineCandidateTaskCompletionException();
        }
        task.setTaskStatus(STATUS_FAILED);
        task.setVersion(taskVersion + 1);
        candidate.setCandidateStatus(CANDIDATE_FAILED);
        candidate.setVersion(candidateVersion + 1);
        return true;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "候选结果无法序列化", exception);
        }
    }

    private int version(com.dugnan.moqi.common.entity.BaseEntity entity) {
        return entity.getVersion() == null ? 0 : entity.getVersion();
    }
}
