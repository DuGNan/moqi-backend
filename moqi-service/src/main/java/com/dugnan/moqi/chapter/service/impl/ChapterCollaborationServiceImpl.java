package com.dugnan.moqi.chapter.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.BriefDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.BriefRequest;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConversationDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageCreated;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageList;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineDetail;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineRequest;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ReplyPolicySnapshot;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.SendMessageRequest;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusImpactService;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.focus.ChapterDiscussionFocusResolver;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.policy.ConversationReplyTaskInputV1;
import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceService;
import com.dugnan.moqi.chapter.policy.ResolvedReplyPolicy;
import com.dugnan.moqi.chapter.service.ChapterCollaborationService;
import com.dugnan.moqi.chapter.stream.ConversationReplyTaskSubmittedEvent;
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
 * @date 2026-07-16
 * @description 实现章节共创会话、消息、简报和大纲业务流程。
 */
@Service
public class ChapterCollaborationServiceImpl implements ChapterCollaborationService {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_OUTDATED = "outdated";
    private static final String ROLE_USER = "user";
    private static final String CONVERSATION_TYPE = "chapter_co_creation";
    private static final String AI_TASK_TYPE = "conversation_reply";
    private static final String AI_TASK_STATUS = "queued";
    private static final Set<String> MESSAGE_ROLES = Set.of(ROLE_USER, "assistant", "system");
    private static final Set<String> BRIEF_STATUSES = Set.of(STATUS_DRAFT);
    private static final Set<String> OUTLINE_STATUSES = Set.of(STATUS_DRAFT, STATUS_CONFIRMED, STATUS_OUTDATED);

    private final WorkMapper workMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterConversationMapper conversationMapper;
    private final ChapterConversationMessageMapper messageMapper;
    private final ChapterBriefMapper briefMapper;
    private final ChapterOutlineQueryMapper outlineMapper;
    private final AiTaskMapper aiTaskMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ChapterDiscussionFocusResolver focusResolver;
    private final ChapterConsensusImpactService impactService;
    private final ReplyPolicyPreferenceService replyPolicyService;
    private final ObjectMapper objectMapper;

    /**
     * 创建章节共创服务。
     *
     * @param workMapper 作品数据访问对象
     * @param chapterMapper 章节数据访问对象
     * @param conversationMapper 会话数据访问对象
     * @param messageMapper 消息数据访问对象
     * @param briefMapper 简报数据访问对象
     * @param outlineMapper 大纲数据访问对象
     * @param aiTaskMapper AI 任务数据访问对象
     * @param eventPublisher 应用事件发布器
     */
    public ChapterCollaborationServiceImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterConversationMapper conversationMapper,
            ChapterConversationMessageMapper messageMapper,
            ChapterBriefMapper briefMapper,
            ChapterOutlineQueryMapper outlineMapper,
            AiTaskMapper aiTaskMapper,
            ApplicationEventPublisher eventPublisher) {
        this(
                workMapper,
                chapterMapper,
                conversationMapper,
                messageMapper,
                briefMapper,
                outlineMapper,
                aiTaskMapper,
                eventPublisher,
                null,
                null,
                null,
                null);
    }

    /**
     * 创建支持讨论对焦的章节共创服务。
     *
     * @param workMapper 作品数据访问对象
     * @param chapterMapper 章节数据访问对象
     * @param conversationMapper 会话数据访问对象
     * @param messageMapper 消息数据访问对象
     * @param briefMapper 简报数据访问对象
     * @param outlineMapper 大纲数据访问对象
     * @param aiTaskMapper AI 任务数据访问对象
     * @param eventPublisher 应用事件发布器
     * @param focusResolver 讨论对焦解析器
     * @param impactService 共识影响判断服务
     */
    public ChapterCollaborationServiceImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterConversationMapper conversationMapper,
            ChapterConversationMessageMapper messageMapper,
            ChapterBriefMapper briefMapper,
            ChapterOutlineQueryMapper outlineMapper,
            AiTaskMapper aiTaskMapper,
            ApplicationEventPublisher eventPublisher,
            ChapterDiscussionFocusResolver focusResolver) {
        this(
                workMapper,
                chapterMapper,
                conversationMapper,
                messageMapper,
                briefMapper,
                outlineMapper,
                aiTaskMapper,
                eventPublisher,
                focusResolver,
                null,
                null,
                null);
    }

    /**
     * 创建支持讨论对焦与大纲共识影响判断的章节共创服务。
     *
     * @param workMapper 作品数据访问对象
     * @param chapterMapper 章节数据访问对象
     * @param conversationMapper 会话数据访问对象
     * @param messageMapper 消息数据访问对象
     * @param briefMapper 简报数据访问对象
     * @param outlineMapper 大纲数据访问对象
     * @param aiTaskMapper AI 任务数据访问对象
     * @param eventPublisher 应用事件发布器
     * @param focusResolver 讨论对焦解析器
     * @param impactService 共识影响判断服务
     * @param replyPolicyService 回复策略服务
     * @param objectMapper JSON 映射器
     */
    @Autowired
    public ChapterCollaborationServiceImpl(
            WorkMapper workMapper,
            ChapterMapper chapterMapper,
            ChapterConversationMapper conversationMapper,
            ChapterConversationMessageMapper messageMapper,
            ChapterBriefMapper briefMapper,
            ChapterOutlineQueryMapper outlineMapper,
            AiTaskMapper aiTaskMapper,
            ApplicationEventPublisher eventPublisher,
            ChapterDiscussionFocusResolver focusResolver,
            ChapterConsensusImpactService impactService,
            ReplyPolicyPreferenceService replyPolicyService,
            ObjectMapper objectMapper) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.briefMapper = briefMapper;
        this.outlineMapper = outlineMapper;
        this.aiTaskMapper = aiTaskMapper;
        this.eventPublisher = eventPublisher;
        this.focusResolver = focusResolver;
        this.impactService = impactService;
        this.replyPolicyService = replyPolicyService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConversationDetail getConversation(Long chapterId) {
        ChapterEntity chapter = requireChapter(chapterId);
        requireWork(chapter.getWorkId());
        ChapterConversationEntity conversation = findActiveConversation(chapterId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "章节会话不存在");
        }
        return conversationDetail(conversation);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public ConversationDetail createOrGetConversation(Long chapterId) {
        ChapterEntity chapter = requireChapter(chapterId);
        requireWork(chapter.getWorkId());
        ChapterConversationEntity conversation = findActiveConversation(chapterId);
        if (conversation == null) {
            conversation = new ChapterConversationEntity();
            conversation.setWorkId(chapter.getWorkId());
            conversation.setChapterId(chapterId);
            conversation.setConversationType(CONVERSATION_TYPE);
            conversation.setConversationStatus(STATUS_ACTIVE);
            conversation.setDeleted(0);
            conversationMapper.insert(conversation);
        }
        return conversationDetail(conversation);
    }

    @Override
    public MessageList listMessages(Long conversationId) {
        ChapterConversationEntity conversation = requireConversation(conversationId);
        List<ChapterConversationMessageEntity> entities = messageMapper.selectList(
                        new LambdaQueryWrapper<ChapterConversationMessageEntity>()
                                .eq(ChapterConversationMessageEntity::getConversationId, conversation.getId())
                                .eq(ChapterConversationMessageEntity::getDeleted, 0)
                                .orderByAsc(ChapterConversationMessageEntity::getGmtCreate)
                                .orderByAsc(ChapterConversationMessageEntity::getId))
                ;
        Map<Long, AiTaskEntity> tasks = taskMap(entities);
        List<MessageDetail> messages = entities.stream()
                .map(entity -> messageDetail(entity, tasks.get(entity.getAiTaskId())))
                .toList();
        return new MessageList(messages);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public MessageCreated sendMessage(Long conversationId, SendMessageRequest request) {
        ChapterConversationEntity conversation = requireConversation(conversationId);
        requireChapter(conversation.getChapterId());
        String role = role(request == null ? null : request.messageRole());
        String content = requiredText(request == null ? null : request.content(), "消息内容不能为空");
        Long continuationMessageId = requireContinuation(conversation, request, role);

        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setConversationId(conversationId);
        message.setChapterId(conversation.getChapterId());
        message.setMessageRole(role);
        message.setContent(content);
        applyDiscussionFocus(conversation, request, role, message);
        message.setDeleted(0);
        messageMapper.insert(message);

        if (request != null && Boolean.TRUE.equals(request.createAiTask())) {
            ResolvedReplyPolicy policy = resolveReplyPolicy(conversation, content, request);
            AiTaskEntity aiTask = new AiTaskEntity();
            aiTask.setTaskType(AI_TASK_TYPE);
            aiTask.setTaskStatus(AI_TASK_STATUS);
            aiTask.setWorkId(conversation.getWorkId());
            aiTask.setChapterId(conversation.getChapterId());
            aiTask.setResultMessageId(null);
            aiTask.setTaskInputJson(taskInputJson(ConversationReplyTaskInputV1.from(
                    message.getId(), conversationId, policy, continuationMessageId)));
            aiTask.setDeleted(0);
            aiTask.setVersion(0);
            aiTaskMapper.insert(aiTask);
            message.setAiTaskId(aiTask.getId());
            messageMapper.updateById(message);
            eventPublisher.publishEvent(new ConversationReplyTaskSubmittedEvent(aiTask.getId()));
        }
        return messageCreated(message);
    }

    private ResolvedReplyPolicy resolveReplyPolicy(
            ChapterConversationEntity conversation,
            String content,
            SendMessageRequest request) {
        if (replyPolicyService == null) {
            return new com.dugnan.moqi.chapter.policy.DefaultReplyPolicyResolver()
                    .resolve(content, request.replyControl(), java.util.Map.of());
        }
        try {
            return replyPolicyService.resolve(conversation.getId(), content, request.replyControl());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, exception.getMessage());
        }
    }

    /**
     * 校验继续展开只能引用本会话中已完成的助手回复。
     */
    private Long requireContinuation(
            ChapterConversationEntity conversation,
            SendMessageRequest request,
            String role) {
        Long continuationMessageId = request == null || request.replyControl() == null
                ? null : request.replyControl().continuationMessageId();
        if (continuationMessageId == null) {
            return null;
        }
        if (!ROLE_USER.equals(role) || !Boolean.TRUE.equals(request.createAiTask())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "continuationMessageId 只允许用于用户 AI 回复请求");
        }
        ChapterConversationMessageEntity target = messageMapper.selectById(continuationMessageId);
        if (target == null || Integer.valueOf(1).equals(target.getDeleted())
                || !conversation.getId().equals(target.getConversationId())
                || !"assistant".equals(target.getMessageRole()) || target.getAiTaskId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "continuationMessageId 必须引用当前会话已完成的助手消息");
        }
        AiTaskEntity task = aiTaskMapper.selectById(target.getAiTaskId());
        if (task == null || !"succeeded".equals(task.getTaskStatus())
                || !continuationMessageId.equals(task.getResultMessageId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "continuationMessageId 必须引用当前会话已完成的助手消息");
        }
        return continuationMessageId;
    }

    private String taskInputJson(ConversationReplyTaskInputV1 input) {
        ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        try {
            return mapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "回复策略快照序列化失败", exception);
        }
    }

    @Override
    public BriefDetail getLatestBrief(Long chapterId) {
        ChapterEntity chapter = requireChapter(chapterId);
        requireWork(chapter.getWorkId());
        ChapterBriefEntity brief = findLatestBrief(chapterId);
        return brief == null ? null : briefDetail(brief);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public BriefDetail saveLatestBrief(Long chapterId, BriefRequest request) {
        ChapterEntity chapter = requireChapter(chapterId);
        requireWork(chapter.getWorkId());
        String content = requiredText(request == null ? null : request.briefContent(), "brief 内容不能为空");
        String status = optionalStatus(request == null ? null : request.briefStatus(), BRIEF_STATUSES, "briefStatus");
        ChapterBriefEntity brief = new ChapterBriefEntity();
        brief.setWorkId(chapter.getWorkId());
        brief.setChapterId(chapterId);
        brief.setDeleted(0);
        brief.setVersion(0);
        brief.setBriefStatus(status);
        brief.setBriefContent(content);
        briefMapper.insert(brief);
        return briefDetail(brief);
    }

    @Override
    public OutlineDetail getOutline(Long chapterId) {
        ChapterEntity chapter = requireChapter(chapterId);
        requireWork(chapter.getWorkId());
        ChapterOutlineEntity outline = outlineMapper.findLatest(chapterId);
        return outline == null ? null : outlineDetail(outline);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public OutlineDetail saveOutline(Long chapterId, OutlineRequest request) {
        ChapterEntity chapter = requireChapter(chapterId);
        requireWork(chapter.getWorkId());
        String content = requiredText(request == null ? null : request.outlineContent(), "大纲内容不能为空");
        String status = optionalStatus(request == null ? null : request.outlineStatus(), OUTLINE_STATUSES, "outlineStatus");
        ChapterBriefEntity confirmedBrief = requireConfirmedBrief(
                chapterId,
                request == null ? null : request.confirmedBriefId());
        ChapterOutlineEntity outline = outlineMapper.findLatest(chapterId);
        if (outline == null) {
            if (request != null && request.baseRevision() != null && request.baseRevision() != 0) {
                throw revisionConflict();
            }
            outline = new ChapterOutlineEntity();
            outline.setWorkId(chapter.getWorkId());
            outline.setChapterId(chapterId);
            outline.setDeleted(0);
            outline.setRevision(0);
            outline.setVersion(0);
        } else if (request == null || request.baseRevision() == null
                || !request.baseRevision().equals(revision(outline))) {
            throw revisionConflict();
        }
        outline.setConfirmedBriefId(confirmedBrief.getId());
        outline.setOutlineStatus(status);
        outline.setOutlineContent(content);
        if (outline.getId() == null) {
            outlineMapper.insert(outline);
        } else {
            int updated = outlineMapper.updateByRevisionAndVersion(
                    outline.getId(), chapterId, confirmedBrief.getId(), status, content,
                    revision(outline), version(outline));
            if (updated != 1) {
                throw revisionConflict();
            }
            outline = outlineMapper.findLatest(chapterId);
        }
        return outlineDetail(outline);
    }

    /**
     * 查询章节活动会话。
     *
     * @param chapterId 章节 ID
     * @return 会话实体
     */
    private ChapterConversationEntity findActiveConversation(Long chapterId) {
        return conversationMapper.selectList(
                        new LambdaQueryWrapper<ChapterConversationEntity>()
                                .eq(ChapterConversationEntity::getChapterId, chapterId)
                                .eq(ChapterConversationEntity::getConversationType, CONVERSATION_TYPE)
                                .eq(ChapterConversationEntity::getConversationStatus, STATUS_ACTIVE)
                                .eq(ChapterConversationEntity::getDeleted, 0)
                                .orderByDesc(ChapterConversationEntity::getGmtModified))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 查询章节最新 brief。
     *
     * @param chapterId 章节 ID
     * @return brief 实体
     */
    private ChapterBriefEntity findLatestBrief(Long chapterId) {
        return briefMapper.selectList(
                        new LambdaQueryWrapper<ChapterBriefEntity>()
                                .eq(ChapterBriefEntity::getChapterId, chapterId)
                                .eq(ChapterBriefEntity::getDeleted, 0)
                                .orderByDesc(ChapterBriefEntity::getGmtModified)
                                .orderByDesc(ChapterBriefEntity::getId))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 查询未删除作品。
     *
     * @param id 作品 ID
     * @return 作品实体
     */
    private WorkEntity requireWork(Long id) {
        WorkEntity entity = id == null ? null : workMapper.selectById(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return entity;
    }

    /**
     * 查询未删除章节。
     *
     * @param id 章节 ID
     * @return 章节实体
     */
    private ChapterEntity requireChapter(Long id) {
        ChapterEntity entity = id == null ? null : chapterMapper.selectById(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
        }
        return entity;
    }

    /**
     * 查询未删除会话。
     *
     * @param id 会话 ID
     * @return 会话实体
     */
    private ChapterConversationEntity requireConversation(Long id) {
        ChapterConversationEntity entity = id == null ? null : conversationMapper.selectById(id);
        if (entity == null || Integer.valueOf(1).equals(entity.getDeleted())) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        return entity;
    }

    /**
     * 规范化消息角色。
     *
     * @param value 消息角色
     * @return 消息角色
     */
    private String role(String value) {
        String role = StringUtils.hasText(value) ? value.trim() : ROLE_USER;
        if (!MESSAGE_ROLES.contains(role)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "messageRole 取值非法");
        }
        return role;
    }

    /**
     * 查询显式指定或最新的已确认 Brief。
     *
     * @param chapterId 章节 ID
     * @param confirmedBriefId 显式 Brief ID
     * @return 已确认 Brief
     */
    private ChapterBriefEntity requireConfirmedBrief(Long chapterId, Long confirmedBriefId) {
        ChapterBriefEntity brief = confirmedBriefId == null
                ? briefMapper.findLatestByChapterIdAndStatus(chapterId, STATUS_CONFIRMED)
                : briefMapper.findByIdAndChapterId(confirmedBriefId, chapterId);
        if (brief == null || !STATUS_CONFIRMED.equals(brief.getBriefStatus())) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_CONFIRMED_BRIEF_REQUIRED,
                    "请先选择本章已确认 Brief");
        }
        return brief;
    }

    /**
     * 校验并持久化讨论对焦引用。
     *
     * @param conversation 当前会话
     * @param request 消息请求
     * @param role 消息角色
     * @param message 待保存消息
     */
    private void applyDiscussionFocus(
            ChapterConversationEntity conversation,
            SendMessageRequest request,
            String role,
            ChapterConversationMessageEntity message) {
        if (request == null || request.discussionFocus() == null) {
            return;
        }
        if (!ROLE_USER.equals(role) || focusResolver == null) {
            throw new BusinessException(
                    ErrorCode.DISCUSSION_FOCUS_INVALID,
                    "discussionFocus 只允许用于用户消息");
        }
        var focus = focusResolver.resolve(
                conversation.getChapterId(),
                conversation.getId(),
                request.discussionFocus().briefId(),
                request.discussionFocus().decisionKey());
        message.setFocusBriefId(focus.briefId());
        message.setFocusDecisionKey(focus.decisionKey());
    }

    /**
     * 规范化状态字段。
     *
     * @param value 原始状态
     * @param allowed 允许状态
     * @param field 字段名
     * @return 状态字段
     */
    private String optionalStatus(String value, Set<String> allowed, String field) {
        String status = StringUtils.hasText(value) ? value.trim() : STATUS_DRAFT;
        if (!allowed.contains(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 取值非法");
        }
        return status;
    }

    /**
     * 校验必填文本。
     *
     * @param value 原始文本
     * @param message 错误消息
     * @return 清理后的文本
     */
    private String requiredText(String value, String message) {
        String text = value == null ? null : value.trim();
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        return text;
    }

    /**
     * 创建大纲版本冲突异常。
     *
     * @return 版本冲突异常
     */
    private BusinessException revisionConflict() {
        return new BusinessException(ErrorCode.OUTLINE_REVISION_CONFLICT, "大纲已被更新，请刷新后重试");
    }

    /**
     * 获取大纲修订版本。
     *
     * @param outline 大纲实体
     * @return 修订版本
     */
    private int revision(ChapterOutlineEntity outline) {
        return outline.getRevision() == null ? 0 : outline.getRevision();
    }

    /**
     * 获取大纲实体乐观锁版本。
     *
     * @param outline 大纲实体
     * @return 非空实体版本
     */
    private int version(ChapterOutlineEntity outline) {
        return outline.getVersion() == null ? 0 : outline.getVersion();
    }

    /**
     * 转换会话详情。
     *
     * @param entity 会话实体
     * @return 会话详情
     */
    private ConversationDetail conversationDetail(ChapterConversationEntity entity) {
        return new ConversationDetail(
                entity.getId(),
                entity.getWorkId(),
                entity.getChapterId(),
                entity.getConversationType(),
                entity.getConversationStatus(),
                entity.getGmtCreate(),
                entity.getGmtModified());
    }

    /**
     * 转换消息详情。
     *
     * @param entity 消息实体
     * @return 消息详情
     */
    private MessageDetail messageDetail(ChapterConversationMessageEntity entity, AiTaskEntity task) {
        return new MessageDetail(
                entity.getId(),
                entity.getConversationId(),
                entity.getChapterId(),
                entity.getMessageRole(),
                entity.getContent(),
                entity.getAiTaskId(),
                entity.getFocusBriefId(),
                entity.getFocusDecisionKey(),
                continuationMessageId(task),
                replyPolicySnapshot(task),
                entity.getGmtCreate(),
                entity.getGmtModified());
    }

    /**
     * 转换已创建消息。
     *
     * @param entity 消息实体
     * @return 已创建消息
     */
    private MessageCreated messageCreated(ChapterConversationMessageEntity entity) {
        AiTaskEntity task = entity.getAiTaskId() == null ? null : aiTaskMapper.selectById(entity.getAiTaskId());
        return new MessageCreated(
                entity.getId(),
                entity.getConversationId(),
                entity.getChapterId(),
                entity.getMessageRole(),
                entity.getContent(),
                entity.getAiTaskId(),
                entity.getFocusBriefId(),
                entity.getFocusDecisionKey(),
                continuationMessageId(task),
                replyPolicySnapshot(task),
                entity.getGmtCreate(),
                entity.getGmtModified());
    }

    /**
     * 从任务快照读取安全的继续展开引用；旧任务和异常 JSON 按无引用兼容。
     */
    private Long continuationMessageId(AiTaskEntity task) {
        ConversationReplyTaskInputV1 input = replyTaskInput(task);
        if (input == null) {
            return null;
        }
        return input.continuationMessageId();
    }

    /**
     * 读取任务的安全策略展示字段，不暴露 Prompt 或上下文正文。
     */
    private ReplyPolicySnapshot replyPolicySnapshot(AiTaskEntity task) {
        ConversationReplyTaskInputV1 input = replyTaskInput(task);
        if (input == null) {
            return null;
        }
        return new ReplyPolicySnapshot(
                input.replyMode().name().toLowerCase(),
                input.replyDepth().name().toLowerCase(),
                input.replyScope().allowedChanges(),
                input.convergenceApplied());
    }

    private ConversationReplyTaskInputV1 replyTaskInput(AiTaskEntity task) {
        if (task == null || !StringUtils.hasText(task.getTaskInputJson())) {
            return null;
        }
        try {
            return (objectMapper == null ? new ObjectMapper() : objectMapper)
                    .readValue(task.getTaskInputJson(), ConversationReplyTaskInputV1.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private Map<Long, AiTaskEntity> taskMap(List<ChapterConversationMessageEntity> entities) {
        List<Long> taskIds = entities.stream().map(ChapterConversationMessageEntity::getAiTaskId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, AiTaskEntity> result = new HashMap<>();
        if (taskIds.isEmpty()) {
            return result;
        }
        for (AiTaskEntity task : aiTaskMapper.selectBatchIds(taskIds)) {
            result.put(task.getId(), task);
        }
        return result;
    }

    /**
     * 转换 brief 详情。
     *
     * @param entity brief 实体
     * @return brief 详情
     */
    private BriefDetail briefDetail(ChapterBriefEntity entity) {
        return new BriefDetail(
                entity.getId(),
                entity.getWorkId(),
                entity.getChapterId(),
                entity.getBriefStatus(),
                entity.getBriefContent(),
                entity.getGmtCreate(),
                entity.getGmtModified());
    }

    /**
     * 转换大纲详情。
     *
     * @param entity 大纲实体
     * @return 大纲详情
     */
    private OutlineDetail outlineDetail(ChapterOutlineEntity entity) {
        ChapterBriefEntity confirmedBrief = entity.getConfirmedBriefId() == null
                ? null
                : briefMapper.findByIdAndChapterId(
                        entity.getConfirmedBriefId(),
                        entity.getChapterId());
        return new OutlineDetail(
                entity.getId(),
                entity.getWorkId(),
                entity.getChapterId(),
                entity.getConfirmedBriefId(),
                entity.getOutlineStatus(),
                entity.getOutlineContent(),
                entity.getRevision(),
                confirmedBrief == null || impactService == null
                        ? null
                        : impactService.assess(
                                confirmedBrief.getBriefContent(),
                                entity.getOutlineContent()),
                entity.getGmtCreate(),
                entity.getGmtModified());
    }
}
