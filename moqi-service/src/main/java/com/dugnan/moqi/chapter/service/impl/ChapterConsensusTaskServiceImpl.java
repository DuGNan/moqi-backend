package com.dugnan.moqi.chapter.service.impl;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusTaskInput;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConsensusTaskCreated;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConsensusTaskRequest;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.service.ChapterConsensusTaskService;
import com.dugnan.moqi.chapter.stream.ChapterConsensusTaskSubmittedEvent;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 校验归属关系并创建可恢复的章节共识异步任务。
 */
@Service
public class ChapterConsensusTaskServiceImpl implements ChapterConsensusTaskService {

    private static final String TASK_TYPE = "chapter_consensus";

    private static final String TASK_STATUS = "queued";

    private static final String TRIGGER_SOURCE_MANUAL = "manual";

    private static final List<String> ACTIVE_TASK_STATUSES = List.of("queued", "running");

    private static final String MESSAGE_ROLE_USER = "user";

    private final WorkMapper workMapper;

    private final ChapterMapper chapterMapper;

    private final ChapterConversationMapper conversationMapper;

    private final ChapterBriefMapper briefMapper;

    private final ChapterConversationMessageMapper messageMapper;

    private final AiTaskMapper taskMapper;

    private final ObjectMapper objectMapper;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建章节共识任务服务。
     *
     * @param workMapper 作品数据访问对象
     * @param chapterMapper 章节数据访问对象
     * @param conversationMapper 会话数据访问对象
     * @param briefMapper Brief 数据访问对象
     * @param messageMapper 会话消息数据访问对象
     * @param taskMapper AI 任务数据访问对象
     * @param objectMapper JSON 映射器
     * @param eventPublisher 应用事件发布器
     */
    public ChapterConsensusTaskServiceImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterConversationMapper conversationMapper,
            ChapterBriefMapper briefMapper,
            ChapterConversationMessageMapper messageMapper,
            AiTaskMapper taskMapper,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.conversationMapper = conversationMapper;
        this.briefMapper = briefMapper;
        this.messageMapper = messageMapper;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ConsensusTaskCreated createTask(Long chapterId, ConsensusTaskRequest request) {
        return createTaskInternal(chapterId, request, TRIGGER_SOURCE_MANUAL, null, null, null, List.of(), List.of());
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ConsensusTaskCreated createAutoTask(Long chapterId, ConsensusTaskRequest request, Long lastMessageId,
            String evaluatorVersion, String idempotencyKey, List<Long> evidenceMessageIds, List<String> reasonCodes) {
        return createTaskInternal(chapterId, request, "auto", lastMessageId, evaluatorVersion, idempotencyKey,
                evidenceMessageIds, reasonCodes);
    }

    private ConsensusTaskCreated createTaskInternal(Long chapterId, ConsensusTaskRequest request,
            String triggerSource, Long lastMessageId, String evaluatorVersion, String idempotencyKey,
            List<Long> evidenceMessageIds, List<String> reasonCodes) {
        ChapterEntity chapter = requireChapter(chapterId);
        requireWork(chapter.getWorkId());
        Long conversationId = request == null ? null : request.conversationId();
        ChapterConversationEntity conversation =
                conversationId == null ? null : conversationMapper.selectById(conversationId);
        if (conversation == null
                || Integer.valueOf(1).equals(conversation.getDeleted())
                || !chapterId.equals(conversation.getChapterId())
                || !chapter.getWorkId().equals(conversation.getWorkId())) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "章节会话不存在");
        }
        Long baseBriefId = request.baseBriefId();
        if (baseBriefId != null) {
            ChapterBriefEntity brief = briefMapper.findByIdAndChapterId(baseBriefId, chapterId);
            if (brief == null) {
                throw new BusinessException(ErrorCode.CHAPTER_BRIEF_NOT_FOUND, "基础 Brief 不存在");
            }
        }

        AiTaskEntity activeTask = findActiveTask(chapterId);
        if (activeTask != null) {
            return new ConsensusTaskCreated(
                    activeTask.getId(),
                    activeTask.getTaskStatus(),
                    chapterId);
        }

        ChapterConversationMessageEntity currentMessage =
                requireLatestUserMessage(chapterId, conversationId);

        AiTaskEntity task = new AiTaskEntity();
        task.setTaskType(TASK_TYPE);
        task.setTaskStatus(TASK_STATUS);
        task.setWorkId(chapter.getWorkId());
        task.setChapterId(chapterId);
        if (TRIGGER_SOURCE_MANUAL.equals(triggerSource)) {
            task.setTaskInputJson(legacyTaskInputJson(conversationId, baseBriefId, currentMessage.getId()));
        } else {
            task.setTaskInputJson(taskInputJson(new ChapterConsensusTaskInput(conversationId, baseBriefId,
                    currentMessage.getId(), triggerSource, lastMessageId, evaluatorVersion, idempotencyKey,
                    evidenceMessageIds == null ? List.of() : evidenceMessageIds,
                    reasonCodes == null ? List.of() : reasonCodes)));
        }
        task.setDeleted(0);
        task.setVersion(0);
        taskMapper.insert(task);
        eventPublisher.publishEvent(new ChapterConsensusTaskSubmittedEvent(task.getId()));
        return new ConsensusTaskCreated(task.getId(), TASK_STATUS, chapterId);
    }

    /**
     * 查询同一章节最近的活动共识任务。
     *
     * @param chapterId 章节 ID
     * @return 活动任务，不存在时返回 null
     */
    private AiTaskEntity findActiveTask(Long chapterId) {
        List<AiTaskEntity> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<AiTaskEntity>()
                        .eq(AiTaskEntity::getChapterId, chapterId)
                        .eq(AiTaskEntity::getTaskType, TASK_TYPE)
                        .in(AiTaskEntity::getTaskStatus, ACTIVE_TASK_STATUSES)
                        .eq(AiTaskEntity::getDeleted, 0)
                        .orderByDesc(AiTaskEntity::getId)
                        .last("LIMIT 1"));
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    /**
     * 查询会话最近一条未删除的用户消息。
     *
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @return 最近一条用户消息
     */
    private ChapterConversationMessageEntity requireLatestUserMessage(
            Long chapterId,
            Long conversationId) {
        List<ChapterConversationMessageEntity> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ChapterConversationMessageEntity>()
                        .eq(ChapterConversationMessageEntity::getChapterId, chapterId)
                        .eq(ChapterConversationMessageEntity::getConversationId, conversationId)
                        .eq(ChapterConversationMessageEntity::getMessageRole, MESSAGE_ROLE_USER)
                        .eq(ChapterConversationMessageEntity::getDeleted, 0)
                        .orderByDesc(ChapterConversationMessageEntity::getId)
                        .last("LIMIT 1"));
        if (messages.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_CONSENSUS_INVALID,
                    "共识任务会话没有可收束的用户消息");
        }
        return messages.get(0);
    }

    /**
     * 序列化只含 ID 引用的任务输入。
     *
     * @param input 任务输入
     * @return JSON
     */
    private String taskInputJson(ChapterConsensusTaskInput input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "共识任务输入无法序列化", exception);
        }
    }

    private String legacyTaskInputJson(Long conversationId, Long baseBriefId, Long currentMessageId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("conversationId", conversationId);
        input.put("baseBriefId", baseBriefId);
        input.put("currentMessageId", currentMessageId);
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "共识任务输入无法序列化", exception);
        }
    }

    /**
     * 查询未删除章节。
     *
     * @param chapterId 章节 ID
     * @return 章节
     */
    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter =
                chapterId == null ? null : chapterMapper.selectByIdForUpdate(chapterId);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    /**
     * 查询未删除作品。
     *
     * @param workId 作品 ID
     */
    private void requireWork(Long workId) {
        WorkEntity work = workId == null ? null : workMapper.selectById(workId);
        if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
    }
}
