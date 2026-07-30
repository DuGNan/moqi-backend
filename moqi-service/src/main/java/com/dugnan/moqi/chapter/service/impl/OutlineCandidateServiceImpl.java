package com.dugnan.moqi.chapter.service.impl;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusImpactService;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConsensusImpact;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineDetail;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.CreateOutlineCandidateRequest;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateConfirmation;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateCreated;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateDetail;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateDiff;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterOutlineCandidateEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterOutlineCandidateMapper;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContentCodec;
import com.dugnan.moqi.chapter.outline.OutlineCandidateTaskInput;
import com.dugnan.moqi.chapter.service.OutlineCandidateService;
import com.dugnan.moqi.chapter.stream.OutlineCandidateEvent;
import com.dugnan.moqi.chapter.stream.OutlineCandidateTaskSubmittedEvent;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 实现大纲调整候选的数据库事实源与确认落版事务。
 */
@Service
public class OutlineCandidateServiceImpl implements OutlineCandidateService {

    private static final String TASK_TYPE = "outline_adjustment_candidate";
    private static final String STATUS_QUEUED = "queued";
    private static final String BRIEF_STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_ABANDONED = "abandoned";
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final int MAX_INSTRUCTION_LENGTH = 2000;

    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterConversationMapper conversationMapper;
    private final ChapterBriefMapper briefMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final ChapterOutlineCandidateMapper candidateMapper;
    private final AiTaskMapper taskMapper;
    private final OutlineCandidateContentCodec contentCodec;
    private final ChapterConsensusImpactService impactService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建候选服务。
     */
    public OutlineCandidateServiceImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterConversationMapper conversationMapper,
            ChapterBriefMapper briefMapper,
            ChapterOutlineQueryMapper outlineMapper,
            ChapterOutlineCandidateMapper candidateMapper,
            AiTaskMapper taskMapper,
            OutlineCandidateContentCodec contentCodec,
            ChapterConsensusImpactService impactService,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.conversationMapper = conversationMapper;
        this.briefMapper = briefMapper;
        this.outlineMapper = outlineMapper;
        this.candidateMapper = candidateMapper;
        this.taskMapper = taskMapper;
        this.contentCodec = contentCodec;
        this.impactService = impactService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public OutlineCandidateCreated create(Long chapterId, CreateOutlineCandidateRequest request) {
        ChapterEntity chapter = requireChapterAndWork(chapterId);
        ChapterConversationEntity conversation = requireConversation(chapter, request == null ? null : request.conversationId());
        ChapterBriefEntity brief = requireCurrentConfirmedBrief(chapterId,
                request == null ? null : request.confirmedBriefId());
        ChapterOutlineEntity outline = requireBaseOutline(chapterId,
                request == null ? null : request.baseOutlineRevision());
        String instruction = requiredInstruction(request == null ? null : request.instruction());

        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(TASK_TYPE);
        task.setTaskStatus(STATUS_QUEUED);
        task.setWorkId(chapter.getWorkId());
        task.setChapterId(chapterId);
        task.setTaskInputJson(taskInputJson(new OutlineCandidateTaskInput(
                conversation.getId(), brief.getId(), outline.getId(), outline.getRevision(), instruction)));
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);

        ChapterOutlineCandidateEntity candidate = new ChapterOutlineCandidateEntity();
        candidate.setWorkId(chapter.getWorkId());
        candidate.setChapterId(chapterId);
        candidate.setConversationId(conversation.getId());
        candidate.setAiTaskId(task.getId());
        candidate.setConfirmedBriefId(brief.getId());
        candidate.setBaseOutlineId(outline.getId());
        candidate.setBaseOutlineRevision(outline.getRevision());
        candidate.setBaseOutlineContent(outline.getOutlineContent());
        candidate.setCandidateStatus(STATUS_QUEUED);
        candidate.setAdjustmentInstruction(instruction);
        candidate.setDeleted(0);
        candidate.setVersion(0);
        candidateMapper.insert(candidate);

        task.setResultOutlineCandidateId(candidate.getId());
        taskMapper.updateById(task);
        eventPublisher.publishEvent(new OutlineCandidateTaskSubmittedEvent(task.getId()));
        eventPublisher.publishEvent(OutlineCandidateEvent.updated(
                chapterId, task.getId(), candidate.getId(), STATUS_QUEUED, STATUS_QUEUED, outline.getId(), outline.getRevision()));
        return new OutlineCandidateCreated(
                chapterId, outline.getId(), outline.getRevision(), candidate.getId(), task.getId(), STATUS_QUEUED);
    }

    @Override
    public OutlineCandidateDetail getLatest(Long chapterId) {
        requireChapterAndWork(chapterId);
        ChapterOutlineCandidateEntity candidate = candidateMapper.findLatest(chapterId);
        return candidate == null ? null : detail(candidate);
    }

    @Override
    public OutlineCandidateDetail get(Long chapterId, Long candidateId) {
        requireChapterAndWork(chapterId);
        return detail(requireCandidate(chapterId, candidateId));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public OutlineCandidateDetail abandon(Long chapterId, Long candidateId) {
        requireChapterAndWork(chapterId);
        ChapterOutlineCandidateEntity candidate = requireCandidate(chapterId, candidateId);
        if (STATUS_ABANDONED.equals(candidate.getCandidateStatus())) {
            return detail(candidate);
        }
        if (STATUS_CONFIRMED.equals(candidate.getCandidateStatus())) {
            throw stateConflict("已确认候选不能放弃");
        }
        if (!STATUS_READY.equals(candidate.getCandidateStatus())) {
            throw stateConflict("仅可放弃已就绪候选，生成中的任务请通过 AI 任务接口取消");
        }
        int version = version(candidate);
        int changed = candidateMapper.update(null, new UpdateWrapper<ChapterOutlineCandidateEntity>()
                .eq("id", candidate.getId())
                .eq("chapter_id", chapterId)
                .eq("deleted", 0)
                .eq("version", version)
                .eq("candidate_status", STATUS_READY)
                .set("candidate_status", STATUS_ABANDONED)
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (changed != 1) {
            throw stateConflict("候选状态已变化，请刷新后重试");
        }
        candidate.setCandidateStatus(STATUS_ABANDONED);
        candidate.setVersion(version + 1);
        eventPublisher.publishEvent(OutlineCandidateEvent.updated(
                chapterId, candidate.getAiTaskId(), candidate.getId(), null, STATUS_ABANDONED, null, null));
        return detail(candidate);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public OutlineCandidateConfirmation confirm(Long chapterId, Long candidateId) {
        requireChapterAndWork(chapterId);
        ChapterOutlineCandidateEntity candidate = candidateMapper.findByIdForUpdate(candidateId, chapterId);
        if (candidate == null) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_NOT_FOUND, "大纲调整候选不存在");
        }
        if (STATUS_CONFIRMED.equals(candidate.getCandidateStatus())) {
            return new OutlineCandidateConfirmation(detail(candidate), outlineDetail(requireOutline(chapterId)));
        }
        if (!STATUS_READY.equals(candidate.getCandidateStatus())) {
            throw stateConflict("候选尚未就绪，不能确认落版");
        }
        requireCurrentConfirmedBrief(chapterId, candidate.getConfirmedBriefId());
        ChapterOutlineEntity outline = requireOutline(chapterId);
        if (!candidate.getBaseOutlineId().equals(outline.getId())
                || !candidate.getBaseOutlineRevision().equals(outline.getRevision())) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_STALE, "正式大纲已更新，请重新生成调整候选");
        }
        OutlineCandidateContent content = contentCodec.read(candidate.getCandidateContent());
        int changedOutline = outlineMapper.updateByRevisionAndVersion(
                outline.getId(), chapterId, candidate.getConfirmedBriefId(), outline.getOutlineStatus(),
                contentCodec.write(content), outline.getRevision(), version(outline));
        if (changedOutline != 1) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_STALE, "正式大纲已更新，请重新生成调整候选");
        }
        int candidateVersion = version(candidate);
        int changedCandidate = candidateMapper.update(null, new UpdateWrapper<ChapterOutlineCandidateEntity>()
                .eq("id", candidate.getId())
                .eq("chapter_id", chapterId)
                .eq("deleted", 0)
                .eq("version", candidateVersion)
                .eq("candidate_status", STATUS_READY)
                .set("candidate_status", STATUS_CONFIRMED)
                .set("result_outline_id", outline.getId())
                .set("result_outline_revision", outline.getRevision() + 1)
                .set("version", candidateVersion + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (changedCandidate != 1) {
            throw stateConflict("候选状态已变化，请刷新后重试");
        }
        candidate.setCandidateStatus(STATUS_CONFIRMED);
        candidate.setResultOutlineId(outline.getId());
        candidate.setResultOutlineRevision(outline.getRevision() + 1);
        candidate.setVersion(candidateVersion + 1);
        ChapterOutlineEntity confirmedOutline = requireOutline(chapterId);
        eventPublisher.publishEvent(OutlineCandidateEvent.updated(
                chapterId, candidate.getAiTaskId(), candidate.getId(), "succeeded", STATUS_CONFIRMED,
                confirmedOutline.getId(), confirmedOutline.getRevision()));
        return new OutlineCandidateConfirmation(detail(candidate), outlineDetail(confirmedOutline));
    }

    private ChapterEntity requireChapterAndWork(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        WorkEntity work = chapter.getWorkId() == null ? null : workMapper.selectById(chapter.getWorkId());
        if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return chapter;
    }

    private ChapterConversationEntity requireConversation(ChapterEntity chapter, Long conversationId) {
        ChapterConversationEntity conversation = conversationId == null ? null : conversationMapper.selectById(conversationId);
        if (conversation == null || Integer.valueOf(1).equals(conversation.getDeleted())
                || !chapter.getId().equals(conversation.getChapterId())
                || !chapter.getWorkId().equals(conversation.getWorkId())) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "章节会话不存在");
        }
        return conversation;
    }

    private ChapterBriefEntity requireCurrentConfirmedBrief(Long chapterId, Long briefId) {
        ChapterBriefEntity brief = briefId == null ? null : briefMapper.findByIdAndChapterId(briefId, chapterId);
        ChapterBriefEntity latest = briefMapper.findLatestByChapterIdAndStatus(chapterId, BRIEF_STATUS_CONFIRMED);
        if (brief == null || !BRIEF_STATUS_CONFIRMED.equals(brief.getBriefStatus())) {
            throw new BusinessException(ErrorCode.CHAPTER_CONFIRMED_BRIEF_REQUIRED, "请先选择已确认的本章 Brief");
        }
        if (latest == null || !brief.getId().equals(latest.getId())) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_BRIEF_STALE, "已确认 Brief 已被替换，请重新生成调整候选");
        }
        return brief;
    }

    private ChapterOutlineEntity requireBaseOutline(Long chapterId, Integer baseRevision) {
        ChapterOutlineEntity outline = requireOutline(chapterId);
        if (baseRevision == null || !baseRevision.equals(outline.getRevision())) {
            throw new BusinessException(ErrorCode.OUTLINE_REVISION_CONFLICT, "大纲已被更新，请刷新后重试");
        }
        return outline;
    }

    private ChapterOutlineEntity requireOutline(Long chapterId) {
        ChapterOutlineEntity outline = outlineMapper.findLatest(chapterId);
        if (outline == null || Integer.valueOf(1).equals(outline.getDeleted())) {
            throw new BusinessException(ErrorCode.OUTLINE_NOT_FOUND, "章节大纲不存在");
        }
        return outline;
    }

    private ChapterOutlineCandidateEntity requireCandidate(Long chapterId, Long candidateId) {
        ChapterOutlineCandidateEntity candidate = candidateId == null ? null : candidateMapper.selectById(candidateId);
        if (candidate == null || Integer.valueOf(1).equals(candidate.getDeleted())
                || !chapterId.equals(candidate.getChapterId())) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_NOT_FOUND, "大纲调整候选不存在");
        }
        return candidate;
    }

    private String requiredInstruction(String instruction) {
        String normalized = instruction == null ? "" : instruction.trim();
        if (!StringUtils.hasText(normalized) || normalized.length() > MAX_INSTRUCTION_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "调整要求不能为空且长度不能超过 2000 字符");
        }
        return normalized;
    }

    private String taskInputJson(OutlineCandidateTaskInput input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "候选任务输入无法序列化", exception);
        }
    }

    private OutlineCandidateDetail detail(ChapterOutlineCandidateEntity candidate) {
        return new OutlineCandidateDetail(
                candidate.getId(), candidate.getWorkId(), candidate.getChapterId(), candidate.getConversationId(),
                candidate.getAiTaskId(), candidate.getConfirmedBriefId(), candidate.getBaseOutlineId(),
                candidate.getBaseOutlineRevision(), contentCodec.read(candidate.getBaseOutlineContent()),
                candidate.getCandidateStatus(), candidate.getAdjustmentInstruction(),
                readOrNull(candidate.getCandidateContent(), OutlineCandidateContent.class),
                readOrNull(candidate.getDiffJson(), OutlineCandidateDiff.class),
                readOrNull(candidate.getConsensusImpactJson(), ConsensusImpact.class), candidate.getResultOutlineId(),
                candidate.getResultOutlineRevision(), candidate.getGmtCreate(), candidate.getGmtModified());
    }

    private OutlineDetail outlineDetail(ChapterOutlineEntity outline) {
        ChapterBriefEntity brief = outline.getConfirmedBriefId() == null ? null
                : briefMapper.findByIdAndChapterId(outline.getConfirmedBriefId(), outline.getChapterId());
        ConsensusImpact impact = brief == null ? null : impactService.assess(brief.getBriefContent(), outline.getOutlineContent());
        return new OutlineDetail(outline.getId(), outline.getWorkId(), outline.getChapterId(), outline.getConfirmedBriefId(),
                outline.getOutlineStatus(), outline.getOutlineContent(), outline.getRevision(), impact,
                outline.getGmtCreate(), outline.getGmtModified());
    }

    private <T> T readOrNull(String json, Class<T> type) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "候选持久化数据无法读取", exception);
        }
    }

    private int version(com.dugnan.moqi.common.entity.BaseEntity entity) {
        return entity.getVersion() == null ? 0 : entity.getVersion();
    }

    private BusinessException stateConflict(String message) {
        return new BusinessException(ErrorCode.OUTLINE_CANDIDATE_STATE_CONFLICT, message);
    }
}
