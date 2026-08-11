package com.dugnan.moqi.chapter.service.impl;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.SceneRevisionOutlineCandidateCommand;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.UpdateOutlineCandidateRequest;
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
import com.dugnan.moqi.chapter.outline.OutlineCandidateDiffService;
import com.dugnan.moqi.chapter.service.OutlineCandidateService;
import com.dugnan.moqi.chapter.stream.OutlineCandidateEvent;
import com.dugnan.moqi.chapter.stream.OutlineCandidateTaskSubmittedEvent;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.ScenePlanDiff;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;
import com.dugnan.moqi.sourcechain.SourcePropagationService;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 实现首版和调整大纲候选的数据库事实源、编辑与确认落版事务。
 */
@Service
public class OutlineCandidateServiceImpl implements OutlineCandidateService {

    private static final String TASK_TYPE = "outline_adjustment_candidate";
    private static final String TYPE_INITIAL = "initial";
    private static final String TYPE_ADJUSTMENT = "adjustment";
    private static final int TASK_INPUT_SCHEMA_VERSION = 2;
    private static final String STATUS_QUEUED = "queued";
    private static final String BRIEF_STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_ABANDONED = "abandoned";
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final int MAX_INSTRUCTION_LENGTH = 2000;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String DEFAULT_INITIAL_INSTRUCTION = "根据已确认的本章共识生成完整的首版章纲";

    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterConversationMapper conversationMapper;
    private final ChapterBriefMapper briefMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final ChapterOutlineCandidateMapper candidateMapper;
    private final AiTaskMapper taskMapper;
    private final OutlineCandidateContentCodec contentCodec;
    private final ChapterConsensusImpactService impactService;
    private final OutlineCandidateDiffService diffService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private SourcePropagationService sourcePropagationService = SourcePropagationService.noop();

    @Autowired
    public void setSourcePropagationService(SourcePropagationService sourcePropagationService) {
        this.sourcePropagationService = sourcePropagationService;
    }

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
            OutlineCandidateDiffService diffService,
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
        this.diffService = diffService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public OutlineCandidateCreated create(Long chapterId, CreateOutlineCandidateRequest request) {
        return createInternal(chapterId, request, null);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public OutlineCandidateCreated createFromSceneRevision(
            Long chapterId,
            SceneRevisionOutlineCandidateCommand command) {
        if (command == null || command.request() == null || command.sourceScenePlanId() == null
                || command.sourceScenePlanVersion() == null || command.sourceConsistencyReportId() == null
                || !StringUtils.hasText(command.sceneDiffJson())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "场景修订来源不能为空");
        }
        return createInternal(chapterId, command.request(), command);
    }

    private OutlineCandidateCreated createInternal(Long chapterId, CreateOutlineCandidateRequest request,
            SceneRevisionOutlineCandidateCommand source) {
        ChapterEntity chapter = requireChapterAndWorkForUpdate(chapterId);
        ChapterConversationEntity conversation = requireConversation(chapter, request == null ? null : request.conversationId());
        ChapterBriefEntity brief = requireCurrentConfirmedBrief(chapterId,
                request == null ? null : request.confirmedBriefId());
        String candidateType = candidateType(request == null ? null : request.candidateType());
        String idempotencyKey = idempotencyKey(request == null ? null : request.idempotencyKey(), candidateType);
        ChapterOutlineCandidateEntity existing = idempotencyKey == null ? null
                : candidateMapper.findByIdempotencyKey(chapterId, idempotencyKey);
        if (existing != null) {
            if (!candidateType.equals(existing.getCandidateType())
                    || !brief.getId().equals(existing.getConfirmedBriefId())
                    || !conversation.getId().equals(existing.getConversationId())
                    || !matchesSource(existing, source)) {
                throw stateConflict("幂等键已用于不同的候选请求");
            }
            return created(existing);
        }
        ChapterOutlineEntity outline = resolveBaseOutline(chapterId,
                request == null ? null : request.baseOutlineRevision(), candidateType);
        if (TYPE_INITIAL.equals(candidateType)) {
            ChapterOutlineCandidateEntity active = candidateMapper.findActiveInitial(chapterId);
            if (active != null) {
                throw stateConflict("已有首版章纲候选正在生成或等待处理");
            }
        }
        String instruction = instruction(request == null ? null : request.instruction(), candidateType);

        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(TASK_TYPE);
        task.setTaskStatus(STATUS_QUEUED);
        task.setWorkId(chapter.getWorkId());
        task.setChapterId(chapterId);
        task.setTaskInputJson(taskInputJson(new OutlineCandidateTaskInput(
                TASK_INPUT_SCHEMA_VERSION, candidateType, conversation.getId(), brief.getId(),
                outline == null ? null : outline.getId(), outline == null ? null : outline.getRevision(),
                instruction, idempotencyKey)));
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);

        ChapterOutlineCandidateEntity candidate = new ChapterOutlineCandidateEntity();
        candidate.setWorkId(chapter.getWorkId());
        candidate.setChapterId(chapterId);
        candidate.setConversationId(conversation.getId());
        candidate.setAiTaskId(task.getId());
        candidate.setConfirmedBriefId(brief.getId());
        candidate.setCandidateType(candidateType);
        candidate.setIdempotencyKey(idempotencyKey);
        if (source != null) {
            candidate.setSourceScenePlanId(source.sourceScenePlanId());
            candidate.setSourceScenePlanVersion(source.sourceScenePlanVersion());
            candidate.setSourceConsistencyReportId(source.sourceConsistencyReportId());
            candidate.setSceneDiffJson(source.sceneDiffJson());
        }
        candidate.setBaseOutlineId(outline == null ? null : outline.getId());
        candidate.setBaseOutlineRevision(outline == null ? null : outline.getRevision());
        candidate.setBaseOutlineContent(outline == null ? null : outline.getOutlineContent());
        candidate.setCandidateStatus(STATUS_QUEUED);
        candidate.setAdjustmentInstruction(instruction);
        candidate.setDeleted(0);
        candidate.setVersion(0);
        candidate.setContentSchemaVersion(OutlineCandidateContent.SCHEMA_VERSION);
        candidate.setMigrationReviewStatus("not_required");
        candidateMapper.insert(candidate);

        task.setResultOutlineCandidateId(candidate.getId());
        taskMapper.updateById(task);
        eventPublisher.publishEvent(new OutlineCandidateTaskSubmittedEvent(task.getId()));
        eventPublisher.publishEvent(OutlineCandidateEvent.updated(
                chapterId, task.getId(), candidate.getId(), STATUS_QUEUED, STATUS_QUEUED,
                candidate.getBaseOutlineId(), candidate.getBaseOutlineRevision()));
        return created(candidate);
    }

    private boolean matchesSource(ChapterOutlineCandidateEntity candidate,
            SceneRevisionOutlineCandidateCommand source) {
        if (source == null) {
            return candidate.getSourceScenePlanId() == null;
        }
        return source.sourceScenePlanId().equals(candidate.getSourceScenePlanId())
                && source.sourceScenePlanVersion().equals(candidate.getSourceScenePlanVersion())
                && source.sourceConsistencyReportId().equals(candidate.getSourceConsistencyReportId())
                && source.sceneDiffJson().equals(candidate.getSceneDiffJson());
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
    public OutlineCandidateDetail update(
            Long chapterId,
            Long candidateId,
            UpdateOutlineCandidateRequest request) {
        requireChapterAndWork(chapterId);
        ChapterOutlineCandidateEntity candidate = requireCandidate(chapterId, candidateId);
        if (!STATUS_READY.equals(candidate.getCandidateStatus())) {
            throw stateConflict("仅可编辑已就绪候选");
        }
        if (request == null || request.baseCandidateVersion() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "baseCandidateVersion 不能为空");
        }
        OutlineCandidateContent content = contentCodec.normalize(request.candidateContent());
        ChapterBriefEntity brief = requireCurrentConfirmedBrief(chapterId, candidate.getConfirmedBriefId());
        OutlineCandidateDiff diff = isInitial(candidate) ? null
                : diffService.diff(contentCodec.read(candidate.getBaseOutlineContent()), content);
        ConsensusImpact impact = impactService.assess(brief.getBriefContent(), contentCodec.write(content));
        int changed = candidateMapper.update(null, new UpdateWrapper<ChapterOutlineCandidateEntity>()
                .eq("id", candidateId)
                .eq("chapter_id", chapterId)
                .eq("deleted", 0)
                .eq("candidate_status", STATUS_READY)
                .eq("version", request.baseCandidateVersion())
                .set("candidate_content", contentCodec.write(content))
                .set("content_schema_version", OutlineCandidateContent.SCHEMA_VERSION)
                .set("migration_review_status", "not_required")
                .set("migration_reason_codes_json", null)
                .set("diff_json", writeOrNull(diff))
                .set("consensus_impact_json", writeOrNull(impact))
                .set("version", request.baseCandidateVersion() + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (changed != 1) {
            throw stateConflict("候选版本已变化，请刷新后重试");
        }
        candidate.setCandidateContent(contentCodec.write(content));
        candidate.setDiffJson(writeOrNull(diff));
        candidate.setConsensusImpactJson(writeOrNull(impact));
        candidate.setVersion(request.baseCandidateVersion() + 1);
        return detail(candidate);
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
            ChapterOutlineEntity confirmed = requireOutline(chapterId);
            if (!confirmed.getId().equals(candidate.getResultOutlineId())) {
                throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_STALE, "候选确认结果与正式大纲不一致");
            }
            return new OutlineCandidateConfirmation(detail(candidate), outlineDetail(confirmed));
        }
        if (!STATUS_READY.equals(candidate.getCandidateStatus())) {
            throw stateConflict("候选尚未就绪，不能确认落版");
        }
        requireCurrentConfirmedBrief(chapterId, candidate.getConfirmedBriefId());
        OutlineCandidateContent content = contentCodec.read(candidate.getCandidateContent());
        ChapterOutlineEntity outline = isInitial(candidate)
                ? createInitialOutline(chapterId, candidate, content)
                : updateAdjustmentOutline(chapterId, candidate, content);
        int resultRevision = outline.getRevision();
        int candidateVersion = version(candidate);
        int changedCandidate = candidateMapper.update(null, new UpdateWrapper<ChapterOutlineCandidateEntity>()
                .eq("id", candidate.getId())
                .eq("chapter_id", chapterId)
                .eq("deleted", 0)
                .eq("version", candidateVersion)
                .eq("candidate_status", STATUS_READY)
                .set("candidate_status", STATUS_CONFIRMED)
                .set("result_outline_id", outline.getId())
                .set("result_outline_revision", resultRevision)
                .set("version", candidateVersion + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (changedCandidate != 1) {
            throw stateConflict("候选状态已变化，请刷新后重试");
        }
        candidate.setCandidateStatus(STATUS_CONFIRMED);
        candidate.setResultOutlineId(outline.getId());
        candidate.setResultOutlineRevision(resultRevision);
        candidate.setVersion(candidateVersion + 1);
        ChapterOutlineEntity confirmedOutline = requireOutline(chapterId);
        sourcePropagationService.outlineConfirmed(chapterId, confirmedOutline.getId());
        if (candidate.getSourceScenePlanId() != null) {
            sourcePropagationService.sceneRevisionRequiresReview(
                    chapterId, candidate.getSourceScenePlanId(), confirmedOutline.getId());
        }
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

    private ChapterEntity requireChapterAndWorkForUpdate(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectByIdForUpdate(chapterId);
        if (chapter == null) {
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

    private ChapterOutlineEntity resolveBaseOutline(Long chapterId, Integer baseRevision, String candidateType) {
        ChapterOutlineEntity outline = outlineMapper.findLatest(chapterId);
        if (TYPE_INITIAL.equals(candidateType)) {
            if (outline != null && !Integer.valueOf(1).equals(outline.getDeleted())) {
                throw stateConflict("正式大纲已存在，请改为生成调整候选");
            }
            if (baseRevision != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "首版章纲不能指定 baseOutlineRevision");
            }
            return null;
        }
        return requireBaseOutline(chapterId, baseRevision);
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

    private String instruction(String instruction, String candidateType) {
        String normalized = instruction == null ? "" : instruction.trim();
        if (!StringUtils.hasText(normalized) && TYPE_INITIAL.equals(candidateType)) {
            return DEFAULT_INITIAL_INSTRUCTION;
        }
        if (!StringUtils.hasText(normalized) || normalized.length() > MAX_INSTRUCTION_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "候选要求不能为空且长度不能超过 2000 字符");
        }
        return normalized;
    }

    private String candidateType(String candidateType) {
        String normalized = StringUtils.hasText(candidateType) ? candidateType.trim() : TYPE_ADJUSTMENT;
        if (!TYPE_INITIAL.equals(normalized) && !TYPE_ADJUSTMENT.equals(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "candidateType 仅支持 initial 或 adjustment");
        }
        return normalized;
    }

    private String idempotencyKey(String idempotencyKey, String candidateType) {
        String normalized = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (!StringUtils.hasText(normalized)) {
            if (TYPE_INITIAL.equals(candidateType)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "首版章纲请求必须提供 idempotencyKey");
            }
            return null;
        }
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "idempotencyKey 长度不能超过 128 字符");
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
                candidate.getAiTaskId(), candidate.getConfirmedBriefId(), candidate.getCandidateType(),
                candidate.getIdempotencyKey(), candidate.getSourceScenePlanId(), candidate.getSourceScenePlanVersion(),
                candidate.getSourceConsistencyReportId(), readOrNull(candidate.getSceneDiffJson(), ScenePlanDiff.class),
                version(candidate), candidate.getBaseOutlineId(),
                candidate.getBaseOutlineRevision(), readOutlineOrNull(candidate.getBaseOutlineContent()),
                candidate.getCandidateStatus(), candidate.getAdjustmentInstruction(),
                readOutlineOrNull(candidate.getCandidateContent()),
                readOrNull(candidate.getDiffJson(), OutlineCandidateDiff.class),
                readOrNull(candidate.getConsensusImpactJson(), ConsensusImpact.class), candidate.getResultOutlineId(),
                candidate.getResultOutlineRevision(), candidate.getContentSchemaVersion(),
                candidate.getMigrationReviewStatus(), readReasonCodes(candidate.getMigrationReasonCodesJson()),
                candidate.getGmtCreate(), candidate.getGmtModified());
    }

    private OutlineCandidateCreated created(ChapterOutlineCandidateEntity candidate) {
        AiTaskEntity task = taskMapper.selectById(candidate.getAiTaskId());
        return new OutlineCandidateCreated(
                candidate.getChapterId(), candidate.getBaseOutlineId(), candidate.getBaseOutlineRevision(),
                candidate.getId(), candidate.getAiTaskId(),
                task == null ? candidate.getCandidateStatus() : task.getTaskStatus(),
                candidate.getCandidateType(), candidate.getIdempotencyKey());
    }

    private ChapterOutlineEntity createInitialOutline(
            Long chapterId,
            ChapterOutlineCandidateEntity candidate,
            OutlineCandidateContent content) {
        if (outlineMapper.findLatest(chapterId) != null) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_STALE, "正式大纲已存在，首版候选不能覆盖");
        }
        ChapterOutlineEntity outline = new ChapterOutlineEntity();
        outline.setWorkId(candidate.getWorkId());
        outline.setChapterId(chapterId);
        outline.setConfirmedBriefId(candidate.getConfirmedBriefId());
        outline.setOutlineStatus(STATUS_CONFIRMED);
        outline.setOutlineContent(contentCodec.write(content));
        outline.setRevision(0);
        outline.setContentSchemaVersion(OutlineCandidateContent.SCHEMA_VERSION);
        outline.setMigrationReviewStatus("not_required");
        outline.setDeleted(0);
        outline.setVersion(0);
        try {
            outlineMapper.insert(outline);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new BusinessException(
                    ErrorCode.OUTLINE_CANDIDATE_STALE, "正式大纲已被其他请求创建，请刷新后重试", exception);
        }
        return outline;
    }

    private ChapterOutlineEntity updateAdjustmentOutline(
            Long chapterId,
            ChapterOutlineCandidateEntity candidate,
            OutlineCandidateContent content) {
        ChapterOutlineEntity outline = requireOutline(chapterId);
        if (!candidate.getBaseOutlineId().equals(outline.getId())
                || !candidate.getBaseOutlineRevision().equals(outline.getRevision())) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_STALE, "正式大纲已更新，请重新生成调整候选");
        }
        int changed = outlineMapper.updateByRevisionAndVersion(
                outline.getId(), chapterId, candidate.getConfirmedBriefId(), outline.getOutlineStatus(),
                contentCodec.write(content), outline.getRevision(), version(outline));
        if (changed != 1) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_STALE, "正式大纲已更新，请重新生成调整候选");
        }
        outline.setRevision(outline.getRevision() + 1);
        return outline;
    }

    private boolean isInitial(ChapterOutlineCandidateEntity candidate) {
        return TYPE_INITIAL.equals(candidate.getCandidateType());
    }

    private OutlineCandidateContent readOutlineOrNull(String json) {
        return StringUtils.hasText(json) ? contentCodec.read(json) : null;
    }

    private java.util.List<String> readReasonCodes(String json) {
        if (!StringUtils.hasText(json)) {
            return java.util.List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "章纲迁移复核原因无法读取", exception);
        }
    }

    private String writeOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "候选数据无法序列化", exception);
        }
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
