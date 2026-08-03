package com.dugnan.moqi.chapter.workflow;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConsensusTaskRequest;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.service.ChapterConsensusTaskService;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmRole;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 使用可恢复 Agent Runtime 判断讨论成熟度并提交共识草稿任务。
 */
@Component
public class ChapterConsensusMaturityWorkflowDefinition implements AgentWorkflowDefinition {
    private static final String PRECHECK = "precheck";
    private static final String EVALUATE = "evaluate";
    private static final String ENQUEUE = "enqueue_consensus";
    private static final String FIELD_READY = "ready";
    private static final String FIELD_SCHEMA_VERSION = "schemaVersion";
    private static final String FIELD_CONFIDENCE = "confidence";
    private static final String FIELD_EVIDENCE_MESSAGE_IDS = "evidenceMessageIds";
    private static final String FIELD_REASON_CODES = "reasonCodes";
    private static final double MIN_CONFIDENCE = 0D;
    private static final double MAX_CONFIDENCE = 1D;
    private static final int MAX_EVIDENCE_MESSAGE_IDS = 20;
    private static final String REASON_INSUFFICIENT_NEW_MESSAGES = "INSUFFICIENT_NEW_MESSAGES";
    private static final String REASON_ACTIVE_CONSENSUS_TASK = "ACTIVE_CONSENSUS_TASK";
    private static final String REASON_BASE_BRIEF_STALE = "BASE_BRIEF_STALE";
    private final ChapterConversationMessageMapper messageMapper;
    private final AiTaskMapper taskMapper;
    private final ChapterBriefMapper briefMapper;
    private final ChapterConsensusTaskService consensusTaskService;
    private final LlmProviderFactory providerFactory;
    private final UserConfigService userConfigService;
    private final ObjectMapper objectMapper;
    private final int minimumMessages;
    private final double minimumConfidence;
    private final Duration timeout;
    private final int cooldownMinutes;
    private final int maxInputCharacters;

    public ChapterConsensusMaturityWorkflowDefinition(ChapterConversationMessageMapper messageMapper, AiTaskMapper taskMapper,
            ChapterBriefMapper briefMapper,
            ChapterConsensusTaskService consensusTaskService, LlmProviderFactory providerFactory,
            UserConfigService userConfigService, ObjectMapper objectMapper,
            @Value("${moqi.chapter.consensus-maturity.minimum-new-messages:2}") int minimumMessages,
            @Value("${moqi.chapter.consensus-maturity.minimum-confidence:0.75}") double minimumConfidence,
            @Value("${moqi.chapter.consensus-maturity.timeout-seconds:120}") long timeoutSeconds,
            @Value("${moqi.chapter.consensus-maturity.cooldown-minutes:5}") int cooldownMinutes,
            @Value("${moqi.chapter.consensus-maturity.max-input-characters:12000}") int maxInputCharacters) {
        this.messageMapper = messageMapper;
        this.taskMapper = taskMapper;
        this.briefMapper = briefMapper;
        this.consensusTaskService = consensusTaskService;
        this.providerFactory = providerFactory;
        this.userConfigService = userConfigService;
        this.objectMapper = objectMapper;
        this.minimumMessages = minimumMessages;
        this.minimumConfidence = minimumConfidence;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.cooldownMinutes = cooldownMinutes;
        this.maxInputCharacters = maxInputCharacters;
    }

    @Override public String workflowType() { return "chapter_consensus_maturity_v1"; }
    @Override public String startStepKey() { return PRECHECK; }
    @Override public Duration timeout() { return timeout; }
    @Override public int maxAttempts(String stepKey) { return EVALUATE.equals(stepKey) ? 2 : 1; }

    @Override
    public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) throws Exception {
        if (PRECHECK.equals(stepKey)) {
            List<ChapterConversationMessageEntity> messages = messages(context);
            boolean enoughMessages = messages.size() >= minimumMessages;
            boolean eligible = enoughMessages && !hasActiveConsensusTask(chapterId(context))
                    && hasValidBaseBrief(context) && !isCoolingDown(messages);
            return AgentStepResult.completed(Map.of("eligible", eligible, "reasonCodes",
                    eligible ? List.of() : List.of(precheckReason(enoughMessages, context, messages))),
                    Map.of("eligible", eligible),
                    eligible ? EVALUATE : null);
        }
        if (EVALUATE.equals(stepKey)) {
            List<ChapterConversationMessageEntity> messages = messages(context);
            LlmProvider provider = providerFactory.create(userConfigService.requireAvailableModelConfig());
            if (!provider.capabilities().structuredOutput()) {
                throw new IllegalStateException("当前 Provider 不支持结构化成熟度判断");
            }
            String discussion = boundedDiscussion(messages);
            LlmResponse response = provider.generate(new LlmRequest(List.of(
                    new LlmMessage(LlmRole.SYSTEM, "只输出 JSON：schemaVersion=1,ready(boolean),confidence(0-1),"
                            + "changedDecisionKeys(array),evidenceMessageIds(array),reasonCodes(array)。不得输出解释或隐藏推理。"),
                    new LlmMessage(LlmRole.USER, discussion)), new LlmOptions(512, 0D, List.of(), LlmResponseFormat.JSON_OBJECT)));
            JsonNode result = response == null ? null : response.structuredContent();
            if (result == null || result.path(FIELD_SCHEMA_VERSION).asInt(-1) != 1
                    || !result.path(FIELD_READY).isBoolean() || !result.path(FIELD_CONFIDENCE).isNumber()
                    || !result.path("changedDecisionKeys").isArray() || !result.path(FIELD_REASON_CODES).isArray()) {
                throw new IllegalStateException("成熟度结果不符合结构化契约");
            }
            double confidence = result.path(FIELD_CONFIDENCE).asDouble();
            if (confidence < MIN_CONFIDENCE || confidence > MAX_CONFIDENCE
                    || !result.path(FIELD_EVIDENCE_MESSAGE_IDS).isArray()) {
                throw new IllegalStateException("成熟度结果不符合结构化契约");
            }
            List<Long> evidence = objectMapper.convertValue(result.path(FIELD_EVIDENCE_MESSAGE_IDS),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
            Set<Long> messageIds = messages.stream().map(ChapterConversationMessageEntity::getId)
                    .collect(java.util.stream.Collectors.toSet());
            if (evidence.size() > MAX_EVIDENCE_MESSAGE_IDS || evidence.stream().anyMatch(id -> !messageIds.contains(id))) {
                throw new IllegalStateException("成熟度结果引用了无效证据消息");
            }
            boolean ready = result.path(FIELD_READY).asBoolean() && confidence >= minimumConfidence;
            return AgentStepResult.completed(Map.of(FIELD_READY, ready, FIELD_CONFIDENCE, confidence,
                    FIELD_REASON_CODES, objectMapper.convertValue(result.path(FIELD_REASON_CODES), List.class)),
                    Map.of(FIELD_READY, ready, FIELD_EVIDENCE_MESSAGE_IDS, evidence,
                            FIELD_REASON_CODES, objectMapper.convertValue(result.path(FIELD_REASON_CODES), List.class)),
                    ready ? ENQUEUE : null);
        }
        if (ENQUEUE.equals(stepKey)) {
            List<ChapterConversationMessageEntity> messages = messages(context);
            Long lastMessageId = ((Number) context.input().get("assistantMessageId")).longValue();
            if (messages.isEmpty() || !lastMessageId.equals(messages.get(messages.size() - 1).getId())) {
                return AgentStepResult.completed(Map.of("submitted", false, "stale", true), Map.of("stale", true), null);
            }
            consensusTaskService.createAutoTask(chapterId(context),
                    new ConsensusTaskRequest(conversationId(context), baseBriefId(context)), lastMessageId,
                    String.valueOf(context.input().get("evaluatorVersion")), context.runId() + ":" + lastMessageId,
                    ids(context.state().get(FIELD_EVIDENCE_MESSAGE_IDS)), strings(context.state().get(FIELD_REASON_CODES)));
            return AgentStepResult.completed(Map.of("submitted", true), Map.of("submitted", true), null);
        }
        throw new IllegalArgumentException("未知成熟度步骤: " + stepKey);
    }

    private List<ChapterConversationMessageEntity> messages(AgentStepExecutionContext context) {
        return messageMapper.selectList(new LambdaQueryWrapper<ChapterConversationMessageEntity>()
                .eq(ChapterConversationMessageEntity::getConversationId, conversationId(context))
                .eq(ChapterConversationMessageEntity::getChapterId, context.input().get("chapterId"))
                .eq(ChapterConversationMessageEntity::getDeleted, 0).orderByAsc(ChapterConversationMessageEntity::getId));
    }

    private boolean hasActiveConsensusTask(Long chapterId) {
        return !taskMapper.selectList(new LambdaQueryWrapper<AiTaskEntity>().eq(AiTaskEntity::getChapterId, chapterId)
                .eq(AiTaskEntity::getTaskType, "chapter_consensus").in(AiTaskEntity::getTaskStatus, List.of("queued", "running"))
                .eq(AiTaskEntity::getDeleted, 0).last("LIMIT 1")).isEmpty();
    }

    private boolean hasValidBaseBrief(AgentStepExecutionContext context) {
        Long baseBriefId = baseBriefId(context);
        return baseBriefId == null || briefMapper.findByIdAndChapterId(baseBriefId, chapterId(context)) != null;
    }

    private boolean isCoolingDown(List<ChapterConversationMessageEntity> messages) {
        return messages.stream().filter(message -> "assistant".equals(message.getMessageRole()))
                .map(ChapterConversationMessageEntity::getGmtCreate).filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo)
                .map(time -> time.plusMinutes(cooldownMinutes).isAfter(LocalDateTime.now())).orElse(false);
    }

    private String precheckReason(boolean enoughMessages, AgentStepExecutionContext context,
            List<ChapterConversationMessageEntity> messages) {
        if (!enoughMessages) { return REASON_INSUFFICIENT_NEW_MESSAGES; }
        if (hasActiveConsensusTask(chapterId(context))) { return REASON_ACTIVE_CONSENSUS_TASK; }
        if (!hasValidBaseBrief(context)) { return REASON_BASE_BRIEF_STALE; }
        return isCoolingDown(messages) ? "COOLDOWN_ACTIVE" : "PRECHECK_NOT_READY";
    }

    private String boundedDiscussion(List<ChapterConversationMessageEntity> messages) {
        StringBuilder builder = new StringBuilder();
        for (ChapterConversationMessageEntity message : messages) {
            String line = message.getMessageRole() + ": " + message.getContent() + "\n";
            if (builder.length() + line.length() > maxInputCharacters) {
                builder.append(line, 0, Math.max(0, maxInputCharacters - builder.length()));
                break;
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private Long conversationId(AgentStepExecutionContext context) { return ((Number) context.input().get("conversationId")).longValue(); }

    private Long chapterId(AgentStepExecutionContext context) { return ((Number) context.input().get("chapterId")).longValue(); }

    private Long baseBriefId(AgentStepExecutionContext context) {
        Object value = context.input().get("baseBriefId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value instanceof String text && !"none".equals(text) ? Long.valueOf(text) : null;
    }

    private List<Long> ids(Object value) {
        return value == null ? List.of() : objectMapper.convertValue(value,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
    }

    private List<String> strings(Object value) {
        return value == null ? List.of() : objectMapper.convertValue(value,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
