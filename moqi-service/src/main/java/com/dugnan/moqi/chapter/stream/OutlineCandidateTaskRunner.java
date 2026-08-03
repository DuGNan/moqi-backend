package com.dugnan.moqi.chapter.stream;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
 * @date 2026-07-30
 * @description 执行章节大纲调整候选任务并发布只含资源引用的事件。
 */
@Component
public class OutlineCandidateTaskRunner {

    private static final String TASK_TYPE = "outline_adjustment_candidate";
    private static final String BRIEF_STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_RUNNING = "running";
    private static final String TYPE_INITIAL = "initial";
    private static final String ADJUSTMENT_TASK_INSTRUCTION = """
            请根据已确认的章节共识、当前正式大纲和用户调整要求，输出一个完整的 OutlineCandidateContent JSON 对象。
            只能输出 schemaVersion、chapterPurpose、openingState、chapterGoal、coreConflict、beats、turningPoint、endingState、endingHook、constraints 字段。
            每个 beat 必须包含唯一的 beatKey 和 summary；不得写入视角、地点、人物、时间或标签。不得输出解释、Markdown 或其他字段。
            输出是待用户确认的候选，绝不能声称已保存或修改正式大纲。
            """;
    private static final String INITIAL_TASK_INSTRUCTION = """
            请只根据指定的已确认章节共识生成完整的首版 OutlineCandidateContent JSON 对象。
            只能输出 schemaVersion、chapterPurpose、openingState、chapterGoal、coreConflict、beats、turningPoint、endingState、endingHook、constraints 字段。
            每个 beat 必须包含唯一稳定的 beatKey 和 summary；讨论历史只能作为证据，不能把未确认、待定或已否定内容写成权威事实。
            不得输出解释、Markdown 或其他字段。输出仅是待用户编辑和确认的候选，
            绝不能声称已保存、已确认或已成为正式章纲。
            """;

    private final AiTaskMapper taskMapper;
    private final ChapterOutlineCandidateMapper candidateMapper;
    private final ChapterConversationMapper conversationMapper;
    private final ChapterBriefMapper briefMapper;
    private final UserConfigService userConfigService;
    private final LlmProviderFactory providerFactory;
    private final StoryContextTaskBindingService contextBindingService;
    private final OutlineCandidatePersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建候选任务执行器。
     */
    public OutlineCandidateTaskRunner(
            AiTaskMapper taskMapper,
            ChapterOutlineCandidateMapper candidateMapper,
            ChapterConversationMapper conversationMapper,
            ChapterBriefMapper briefMapper,
            UserConfigService userConfigService,
            LlmProviderFactory providerFactory,
            StoryContextTaskBindingService contextBindingService,
            OutlineCandidatePersistenceService persistenceService,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this.taskMapper = taskMapper;
        this.candidateMapper = candidateMapper;
        this.conversationMapper = conversationMapper;
        this.briefMapper = briefMapper;
        this.userConfigService = userConfigService;
        this.providerFactory = providerFactory;
        this.contextBindingService = contextBindingService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 执行一个排队候选任务。
     *
     * @param taskId AI 任务 ID
     */
    public void run(Long taskId) {
        AiTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
        ChapterOutlineCandidateEntity candidate = task == null ? null : candidateMapper.findByTaskId(taskId);
        if (task == null || candidate == null || Integer.valueOf(1).equals(task.getDeleted())
                || !TASK_TYPE.equals(task.getTaskType()) || !persistenceService.claim(task, candidate)) {
            return;
        }
        eventPublisher.publishEvent(OutlineCandidateEvent.updated(
                task.getChapterId(), task.getId(), candidate.getId(), STATUS_RUNNING, STATUS_RUNNING,
                candidate.getBaseOutlineId(), candidate.getBaseOutlineRevision()));
        try {
            OutlineCandidateTaskInput input = taskInput(task);
            ChapterConversationEntity conversation = requireConversation(task, input.conversationId());
            ChapterBriefEntity brief = requireBrief(task.getChapterId(), input.confirmedBriefId());
            LlmProvider provider = providerFactory.create(userConfigService.requireAvailableModelConfig());
            StoryContextSnapshot snapshot = buildContext(task, conversation, candidate, brief, input, provider);
            LlmResponse response = provider.generate(new LlmRequest(
                    snapshot.toMessages(),
                    new LlmOptions(snapshot.outputReserveTokens(), null, List.of(), LlmResponseFormat.JSON_OBJECT)));
            if (response == null || response.structuredContent() == null) {
                throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_INVALID, "模型没有返回结构化大纲候选");
            }
            OutlineCandidateContent content = new OutlineCandidateContentCodec(objectMapper)
                    .read(objectMapper.writeValueAsString(response.structuredContent()));
            persistenceService.complete(task, candidate, brief.getBriefContent(), content);
            eventPublisher.publishEvent(OutlineCandidateEvent.updated(
                    task.getChapterId(), task.getId(), candidate.getId(), "succeeded", "ready",
                    candidate.getBaseOutlineId(), candidate.getBaseOutlineRevision()));
        } catch (OutlineCandidateTaskCompletionException | StoryContextTaskBindingException exception) {
            // 取消或并发终态已获胜，不能覆盖最新状态。
        } catch (JsonProcessingException exception) {
            fail(task, candidate, ErrorCode.OUTLINE_CANDIDATE_INVALID.name(), "模型大纲候选结构无法读取");
        } catch (LlmProviderException exception) {
            fail(task, candidate, exception.getError().name(), exception.getMessage());
        } catch (BusinessException exception) {
            fail(task, candidate, exception.getErrorCode().name(), exception.getMessage());
        } catch (RuntimeException exception) {
            fail(task, candidate, ErrorCode.INTERNAL_ERROR.name(), "大纲调整候选生成失败，请稍后重试");
        }
    }

    /**
     * 将被执行器拒绝的任务写为失败。
     *
     * @param taskId AI 任务 ID
     */
    public void reject(Long taskId) {
        AiTaskEntity task = taskId == null ? null : taskMapper.selectById(taskId);
        ChapterOutlineCandidateEntity candidate = task == null ? null : candidateMapper.findByTaskId(taskId);
        if (task != null && candidate != null && persistenceService.reject(task, candidate)) {
            eventPublisher.publishEvent(OutlineCandidateEvent.updated(
                    task.getChapterId(), task.getId(), candidate.getId(), STATUS_FAILED, STATUS_FAILED,
                    candidate.getBaseOutlineId(), candidate.getBaseOutlineRevision()));
        }
    }

    private StoryContextSnapshot buildContext(
            AiTaskEntity task,
            ChapterConversationEntity conversation,
            ChapterOutlineCandidateEntity candidate,
            ChapterBriefEntity brief,
            OutlineCandidateTaskInput input,
            LlmProvider provider) {
        int contextWindow = provider.capabilities().maxContextTokens() == null
                ? 32768 : provider.capabilities().maxContextTokens();
        int outputReserve = StoryContextProfile.OUTLINE_ADJUSTMENT.defaultOutputReserveTokens();
        if (provider.capabilities().maxOutputTokens() != null) {
            outputReserve = Math.min(outputReserve, provider.capabilities().maxOutputTokens());
        }
        boolean initial = TYPE_INITIAL.equals(input.candidateType());
        String taskInstruction = initial ? INITIAL_TASK_INSTRUCTION : ADJUSTMENT_TASK_INSTRUCTION;
        String targetText = initial
                ? "唯一权威输入（已确认 Brief）：\n" + brief.getBriefContent()
                : "基础正式大纲：\n" + candidate.getBaseOutlineContent()
                    + "\n\n指定已确认 Brief：\n" + brief.getBriefContent();
        return contextBindingService.buildAndAttach(new StoryContextBuildCommand(
                StoryContextProfile.OUTLINE_ADJUSTMENT,
                task.getWorkId(), task.getChapterId(), conversation.getId(), null, taskInstruction,
                input.instruction(), targetText, contextWindow, outputReserve), task);
    }

    private OutlineCandidateTaskInput taskInput(AiTaskEntity task) {
        if (!StringUtils.hasText(task.getTaskInputJson())) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_INVALID, "候选任务缺少输入引用");
        }
        try {
            return objectMapper.readValue(task.getTaskInputJson(), OutlineCandidateTaskInput.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_INVALID, "候选任务输入无法读取", exception);
        }
    }

    private ChapterConversationEntity requireConversation(AiTaskEntity task, Long conversationId) {
        ChapterConversationEntity conversation = conversationId == null ? null : conversationMapper.selectById(conversationId);
        if (conversation == null || Integer.valueOf(1).equals(conversation.getDeleted())
                || !task.getWorkId().equals(conversation.getWorkId())
                || !task.getChapterId().equals(conversation.getChapterId())) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "候选任务会话不存在");
        }
        return conversation;
    }

    private ChapterBriefEntity requireBrief(Long chapterId, Long briefId) {
        ChapterBriefEntity brief = briefId == null ? null : briefMapper.findByIdAndChapterId(briefId, chapterId);
        if (brief == null || !BRIEF_STATUS_CONFIRMED.equals(brief.getBriefStatus())) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_BRIEF_STALE, "候选任务绑定的 Brief 已失效");
        }
        return brief;
    }

    private void fail(AiTaskEntity task, ChapterOutlineCandidateEntity candidate, String errorCode, String errorMessage) {
        if (persistenceService.fail(task, candidate, errorCode, errorMessage)) {
            eventPublisher.publishEvent(OutlineCandidateEvent.updated(
                    task.getChapterId(), task.getId(), candidate.getId(), STATUS_FAILED, STATUS_FAILED,
                    candidate.getBaseOutlineId(), candidate.getBaseOutlineRevision()));
        }
    }
}
