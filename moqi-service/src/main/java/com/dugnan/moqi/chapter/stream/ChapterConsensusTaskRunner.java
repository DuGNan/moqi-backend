package com.dugnan.moqi.chapter.stream;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusTaskInput;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.context.StoryContextBuildCommand;
import com.dugnan.moqi.context.StoryContextProfile;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextTaskBindingException;
import com.dugnan.moqi.context.StoryContextTaskBindingService;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseFormat;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 执行章节共识结构化收束任务并发布安全资源事件。
 */
@Component
public class ChapterConsensusTaskRunner {

    private static final String TASK_TYPE = "chapter_consensus";

    private static final String STATUS_QUEUED = "queued";

    private static final String STATUS_RUNNING = "running";

    private static final String STATUS_FAILED = "failed";

    private static final String MESSAGE_ROLE_USER = "user";

    private static final String TASK_INSTRUCTION = """
            请把章节共创讨论收束为 ChapterConsensusContentV1 JSON 对象。
            只能输出 schemaVersion、chapterTask、stateChange、keyPush、readerProgress、
            writingBoundaries、decisions 字段；decision 状态只能是 confirmed、candidates、
            discussing、pending。sourceMessageIds 只能引用上下文中真实存在的消息 ID。
            这是草稿，不得把 Brief 标记为 confirmed。
            """;

    private final AiTaskMapper taskMapper;

    private final ChapterConversationMapper conversationMapper;

    private final ChapterConversationMessageMapper messageMapper;

    private final ChapterBriefMapper briefMapper;

    private final UserConfigService userConfigService;

    private final LlmProviderFactory providerFactory;

    private final StoryContextTaskBindingService contextBindingService;

    private final ChapterConsensusPersistenceService persistenceService;

    private final ObjectMapper objectMapper;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建共识任务执行器。
     */
    public ChapterConsensusTaskRunner(
            AiTaskMapper taskMapper,
            ChapterConversationMapper conversationMapper,
            ChapterConversationMessageMapper messageMapper,
            ChapterBriefMapper briefMapper,
            UserConfigService userConfigService,
            LlmProviderFactory providerFactory,
            StoryContextTaskBindingService contextBindingService,
            ChapterConsensusPersistenceService persistenceService,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this.taskMapper = taskMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.briefMapper = briefMapper;
        this.userConfigService = userConfigService;
        this.providerFactory = providerFactory;
        this.contextBindingService = contextBindingService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 执行一个 queued 共识任务。
     *
     * @param taskId 任务 ID
     */
    public void run(Long taskId) {
        AiTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null
                || Integer.valueOf(1).equals(task.getDeleted())
                || !TASK_TYPE.equals(task.getTaskType())
                || !claim(task)) {
            return;
        }
        try {
            ChapterConsensusTaskInput input = taskInput(task);
            ChapterConversationEntity conversation = requireConversation(task, input.conversationId());
            ChapterConversationMessageEntity currentMessage =
                    requireUserMessage(
                            task.getChapterId(),
                            conversation.getId(),
                            input.currentMessageId());
            LlmProvider provider =
                    providerFactory.create(userConfigService.requireAvailableModelConfig());
            StoryContextSnapshot snapshot =
                    buildContext(task, input, currentMessage, provider);
            LlmResponse response = provider.generate(new LlmRequest(
                    snapshot.toMessages(),
                    new LlmOptions(
                            snapshot.outputReserveTokens(),
                            null,
                            List.of(),
                            LlmResponseFormat.JSON_OBJECT)));
            if (response == null || response.structuredContent() == null) {
                throw new BusinessException(
                        ErrorCode.CHAPTER_CONSENSUS_INVALID,
                        "模型没有返回结构化共识");
            }
            ChapterConsensusContentV1 consensus =
                    objectMapper.treeToValue(response.structuredContent(), ChapterConsensusContentV1.class);
            Long briefId = persistenceService.complete(task, conversation.getId(), consensus);
            eventPublisher.publishEvent(
                    ChapterBriefEvent.draftUpdated(task.getChapterId(), task.getId(), briefId));
        } catch (ChapterConsensusTaskCompletionException | StoryContextTaskBindingException exception) {
            // 任务已被取消或并发完成，不覆盖最新终态。
        } catch (JsonProcessingException exception) {
            fail(task, ErrorCode.CHAPTER_CONSENSUS_INVALID.name(), "模型共识结构无法读取");
        } catch (LlmProviderException exception) {
            fail(task, exception.getError().name(), exception.getMessage());
        } catch (BusinessException exception) {
            fail(task, exception.getErrorCode().name(), exception.getMessage());
        } catch (RuntimeException exception) {
            fail(task, ErrorCode.INTERNAL_ERROR.name(), "章节共识收束失败，请稍后重试");
        }
    }

    /**
     * 将队列拒绝稳定写为失败。
     *
     * @param taskId 任务 ID
     */
    public void reject(Long taskId) {
        AiTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null || !STATUS_QUEUED.equals(task.getTaskStatus())) {
            return;
        }
        int version = version(task);
        taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .eq("task_status", STATUS_QUEUED)
                .set("task_status", STATUS_FAILED)
                .set("error_code", "TASK_QUEUE_FULL")
                .set("error_message", "章节共识任务繁忙，请稍后重试")
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now()));
    }

    /**
     * 使用任务版本条件竞争执行权。
     */
    private boolean claim(AiTaskEntity task) {
        int version = version(task);
        int updated = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .eq("task_status", STATUS_QUEUED)
                .set("task_status", STATUS_RUNNING)
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (updated == 1) {
            task.setTaskStatus(STATUS_RUNNING);
            task.setVersion(version + 1);
            return true;
        }
        return false;
    }

    /**
     * 构建并绑定可审计上下文快照。
     */
    private StoryContextSnapshot buildContext(
            AiTaskEntity task,
            ChapterConsensusTaskInput input,
            ChapterConversationMessageEntity currentMessage,
            LlmProvider provider) {
        int contextWindow = provider.capabilities().maxContextTokens() == null
                ? 32768 : provider.capabilities().maxContextTokens();
        int outputReserve = StoryContextProfile.CHAPTER_DISCUSSION.defaultOutputReserveTokens();
        if (provider.capabilities().maxOutputTokens() != null) {
            outputReserve = Math.min(outputReserve, provider.capabilities().maxOutputTokens());
        }
        return contextBindingService.buildAndAttach(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION,
                task.getWorkId(),
                task.getChapterId(),
                input.conversationId(),
                currentMessage.getId(),
                TASK_INSTRUCTION,
                currentMessage.getContent(),
                baseBriefContent(task.getChapterId(), input.baseBriefId()),
                contextWindow,
                outputReserve), task);
    }

    /**
     * 读取可恢复任务输入。
     */
    private ChapterConsensusTaskInput taskInput(AiTaskEntity task) {
        if (!StringUtils.hasText(task.getTaskInputJson())) {
            throw new BusinessException(ErrorCode.CHAPTER_CONSENSUS_INVALID, "共识任务缺少输入引用");
        }
        try {
            return objectMapper.readValue(task.getTaskInputJson(), ChapterConsensusTaskInput.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_CONSENSUS_INVALID,
                    "共识任务输入引用无法读取",
                    exception);
        }
    }

    /**
     * 重验任务会话归属。
     */
    private ChapterConversationEntity requireConversation(AiTaskEntity task, Long conversationId) {
        ChapterConversationEntity conversation =
                conversationId == null ? null : conversationMapper.selectById(conversationId);
        if (conversation == null
                || Integer.valueOf(1).equals(conversation.getDeleted())
                || !task.getWorkId().equals(conversation.getWorkId())
                || !task.getChapterId().equals(conversation.getChapterId())) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "共识任务会话不存在");
        }
        return conversation;
    }

    /**
     * 读取任务会话最近一条用户消息作为当前输入。
     */
    private ChapterConversationMessageEntity requireUserMessage(
            Long chapterId,
            Long conversationId,
            Long currentMessageId) {
        ChapterConversationMessageEntity message =
                currentMessageId == null ? null : messageMapper.selectById(currentMessageId);
        if (message == null
                || Integer.valueOf(1).equals(message.getDeleted())
                || !chapterId.equals(message.getChapterId())
                || !conversationId.equals(message.getConversationId())
                || !MESSAGE_ROLE_USER.equals(message.getMessageRole())) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_CONSENSUS_INVALID,
                    "共识任务会话没有可收束的用户消息");
        }
        return message;
    }

    /**
     * 读取显式基础 Brief 内容。
     */
    private String baseBriefContent(Long chapterId, Long baseBriefId) {
        if (baseBriefId == null) {
            return null;
        }
        ChapterBriefEntity brief = briefMapper.findByIdAndChapterId(baseBriefId, chapterId);
        if (brief == null) {
            throw new BusinessException(ErrorCode.CHAPTER_BRIEF_NOT_FOUND, "基础 Brief 不存在");
        }
        return brief.getBriefContent();
    }

    /**
     * 以当前运行版本尝试写入失败终态。
     */
    private void fail(AiTaskEntity task, String errorCode, String errorMessage) {
        int version = version(task);
        taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .eq("task_status", STATUS_RUNNING)
                .set("task_status", STATUS_FAILED)
                .set("error_code", errorCode)
                .set("error_message", errorMessage)
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now()));
    }

    /**
     * 获取任务版本。
     */
    private int version(AiTaskEntity task) {
        return task.getVersion() == null ? 0 : task.getVersion();
    }
}
