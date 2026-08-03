package com.dugnan.moqi.chapter.stream;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusJsonException;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusResponseParser;
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
import com.dugnan.moqi.context.StoryContextItem;
import com.dugnan.moqi.context.StoryContextProfile;
import com.dugnan.moqi.context.StoryContextSnapshot;
import com.dugnan.moqi.context.StoryContextSourceType;
import com.dugnan.moqi.context.StoryContextTaskBindingException;
import com.dugnan.moqi.context.StoryContextTaskBindingService;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderError;
import com.dugnan.moqi.llm.LlmProviderException;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmRole;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 执行章节共识结构化收束任务并发布安全资源事件。
 */
@Component
public class ChapterConsensusTaskRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChapterConsensusTaskRunner.class);

    private static final String TASK_TYPE = "chapter_consensus";

    private static final String STATUS_QUEUED = "queued";

    private static final String STATUS_RUNNING = "running";

    private static final String STATUS_FAILED = "failed";

    private static final String MESSAGE_ROLE_USER = "user";

    private static final String JSON_ERROR_CODE = "CHAPTER_CONSENSUS_JSON_INVALID";

    private static final String CONSENSUS_SAFE_MESSAGE = "章节共识不符合结构化契约";

    private static final String REPAIR_INSTRUCTION = "上一次输出未通过结构化契约校验。仅重新输出完整合法的 JSON object；"
            + "不得输出 Markdown、解释或额外字段，所有 sourceQuotes 必须逐字摘自对应 sourceMessageIds 的原消息。";

    private static final String TASK_INSTRUCTION = """
            请把章节共创讨论收束为且仅为一个 ChapterConsensusContentV1 JSON object。
            必须完整输出以下字段，不得增加字段：
            {
              "schemaVersion": 1,
              "chapterTask": "string",
              "stateChange": {"from": "string", "to": "string"},
              "keyPush": "string",
              "readerProgress": {"payoff": "string", "openQuestion": "string"},
              "writingBoundaries": ["string"],
              "decisions": [{
                "key": "lower_snake_case",
                "title": "string",
                "status": "confirmed|candidates|discussing|pending",
                "required": true,
                "prompt": "string",
                "candidateSummary": "string",
                "sourceMessageIds": [1],
                "sourceQuotes": [{"messageId": 1, "quote": "来自原消息的连续原文"}]
              }]
            }
            writingBoundaries、decisions 和 sourceMessageIds 必须是 JSON array，允许为空。
            sourceMessageIds 只能引用上下文中 [sourceMessageIds=[...]] 标出的真实消息 ID。
            如果基础 Brief 中存在 status 为 confirmed、rejected 或 discussing 的 decisions，它们都是用户已作出的决定：
            必须保留原 key、状态、候选摘要和来源，不得删除或改回 candidates、pending；只能为未处理或新增事项给出候选。
            这是草稿，不得把 Brief 标记为 confirmed，不得输出 Markdown 或解释文字。
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

    private final ChapterConsensusResponseParser responseParser;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建共识任务执行器。
     *
     * @param taskMapper AI 任务数据访问对象
     * @param conversationMapper 会话数据访问对象
     * @param messageMapper 会话消息数据访问对象
     * @param briefMapper Brief 数据访问对象
     * @param userConfigService 用户配置服务
     * @param providerFactory LLM Provider 工厂
     * @param contextBindingService 故事上下文绑定服务
     * @param persistenceService 共识结果持久化服务
     * @param objectMapper JSON 映射器
     * @param responseParser 共识 JSON 契约解析器
     * @param eventPublisher 应用事件发布器
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
            ChapterConsensusResponseParser responseParser,
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
        this.responseParser = responseParser;
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
            String baseBriefContent = baseBriefContent(task.getChapterId(), input.baseBriefId());
            StoryContextSnapshot snapshot =
                    buildContext(task, input, currentMessage, provider, baseBriefContent);
            Long briefId = generateAndPersistWithOneRepair(
                    task, conversation.getId(), snapshot, provider, baseBriefContent);
            eventPublisher.publishEvent(
                    ChapterBriefEvent.draftUpdated(task.getChapterId(), task.getId(), briefId,
                            input.triggerSource()));
        } catch (ChapterConsensusTaskCompletionException | StoryContextTaskBindingException exception) {
            // 任务已被取消或并发完成，不覆盖最新终态。
        } catch (ChapterConsensusJsonException exception) {
            LOGGER.warn(
                    "章节共识 JSON 契约校验失败，taskId={}, chapterId={}, contextSnapshotId={}",
                    task.getId(),
                    task.getChapterId(),
                    task.getContextSnapshotId());
            fail(task, JSON_ERROR_CODE, exception.getMessage());
        } catch (LlmProviderException exception) {
            LOGGER.warn(
                    "章节共识 Provider 调用失败，taskId={}, chapterId={}, providerError={}",
                    task.getId(),
                    task.getChapterId(),
                    exception.getError());
            fail(task, exception.getError().name(), exception.getMessage());
        } catch (BusinessException exception) {
            LOGGER.warn(
                    "章节共识业务校验失败，taskId={}, chapterId={}, errorCode={}",
                    task.getId(),
                    task.getChapterId(),
                    exception.getErrorCode());
            fail(task, exception.getErrorCode().name(), safeBusinessMessage(exception));
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "章节共识任务异常，taskId={}, chapterId={}, exceptionType={}",
                    task.getId(),
                    task.getChapterId(),
                    exception.getClass().getName(),
                    exception);
            fail(task, ErrorCode.INTERNAL_ERROR.name(), "章节共识收束失败，请稍后重试");
        }
    }

    private Long generateAndPersistWithOneRepair(
            AiTaskEntity task,
            Long conversationId,
            StoryContextSnapshot snapshot,
            LlmProvider provider,
            String baseBriefContent) {
        try {
            return generateAndPersist(
                    task, conversationId, provider, providerMessages(snapshot), snapshot.outputReserveTokens(), baseBriefContent);
        } catch (ChapterConsensusJsonException exception) {
            return repairAndPersist(task, conversationId, snapshot, provider, baseBriefContent);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.CHAPTER_CONSENSUS_INVALID) {
                throw exception;
            }
            return repairAndPersist(task, conversationId, snapshot, provider, baseBriefContent);
        } catch (LlmProviderException exception) {
            if (exception.getError() != LlmProviderError.INVALID_RESPONSE) {
                throw exception;
            }
            return repairAndPersist(task, conversationId, snapshot, provider, baseBriefContent);
        }
    }

    private Long repairAndPersist(
            AiTaskEntity task,
            Long conversationId,
            StoryContextSnapshot snapshot,
            LlmProvider provider,
            String baseBriefContent) {
        LOGGER.info("章节共识首次结构化结果无效，使用同一快照自动修复一次，taskId={}, chapterId={}",
                task.getId(), task.getChapterId());
        List<LlmMessage> repairMessages = new ArrayList<>(providerMessages(snapshot));
        repairMessages.add(new LlmMessage(LlmRole.USER, REPAIR_INSTRUCTION));
        return generateAndPersist(
                task, conversationId, provider, repairMessages, snapshot.outputReserveTokens(), baseBriefContent);
    }

    private Long generateAndPersist(
            AiTaskEntity task,
            Long conversationId,
            LlmProvider provider,
            List<LlmMessage> messages,
            int outputReserveTokens,
            String baseBriefContent) {
        LlmResponse response = provider.generate(new LlmRequest(
                messages,
                new LlmOptions(
                        outputReserveTokens,
                        0D,
                        List.of(),
                        LlmResponseFormat.JSON_OBJECT)));
        if (response == null || response.structuredContent() == null) {
            throw new LlmProviderException(LlmProviderError.INVALID_RESPONSE);
        }
        ChapterConsensusContentV1 consensus = responseParser.parse(response.structuredContent());
        return persistenceService.complete(task, conversationId, consensus, baseBriefContent);
    }

    /**
     * 将消息来源 ID 以确定性标签加入共识任务的 Provider 消息。
     *
     * @param snapshot 已持久化上下文快照
     * @return 带可引用消息 ID 的 Provider 消息
     */
    private List<LlmMessage> providerMessages(StoryContextSnapshot snapshot) {
        return snapshot.items().stream()
                .map(this::providerMessage)
                .toList();
    }

    private LlmMessage providerMessage(StoryContextItem item) {
        String content = item.content();
        if (item.sourceType() == StoryContextSourceType.CONVERSATION_TURN
                || item.sourceType() == StoryContextSourceType.USER_INPUT) {
            String sourceIds = item.sourceId().replace(":", ",");
            content = "[sourceMessageIds=[" + sourceIds + "]]\n" + content;
        }
        return new LlmMessage(LlmRole.valueOf(item.messageRole()), content);
    }

    private String safeBusinessMessage(BusinessException exception) {
        if (exception.getErrorCode() == ErrorCode.CHAPTER_CONSENSUS_INVALID) {
            return CONSENSUS_SAFE_MESSAGE;
        }
        return exception.getMessage();
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
     *
     * @param task 待执行任务
     * @return 是否成功取得执行权
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
     *
     * @param task 当前任务
     * @param input 任务输入引用
     * @param currentMessage 当前用户消息
     * @param provider LLM Provider
     * @return 已持久化并绑定的上下文快照
     */
    private StoryContextSnapshot buildContext(
            AiTaskEntity task,
            ChapterConsensusTaskInput input,
            ChapterConversationMessageEntity currentMessage,
            LlmProvider provider,
            String baseBriefContent) {
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
                baseBriefContent,
                contextWindow,
                outputReserve), task);
    }

    /**
     * 读取可恢复任务输入。
     *
     * @param task 当前任务
     * @return 任务输入引用
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
     *
     * @param task 当前任务
     * @param conversationId 会话 ID
     * @return 归属合法的会话实体
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
     *
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @param currentMessageId 当前消息 ID
     * @return 当前用户消息
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
     *
     * @param chapterId 章节 ID
     * @param baseBriefId 基础 Brief ID
     * @return Brief 内容，未指定时返回 null
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
     *
     * @param task 当前任务
     * @param errorCode 错误码
     * @param errorMessage 安全错误消息
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
     *
     * @param task 当前任务
     * @return 非空任务版本
     */
    private int version(AiTaskEntity task) {
        return task.getVersion() == null ? 0 : task.getVersion();
    }
}
