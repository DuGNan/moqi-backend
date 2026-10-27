package com.dugnan.moqi.chapter.selection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.agent.AgentWorkflowDefinition;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepExecutionContext;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentStepResult;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ConversationHistoryMessage;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ModelPlanningProposal;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.config.service.UserConfigService;
import com.dugnan.moqi.llm.LlmCallContext;
import com.dugnan.moqi.llm.LlmExecutionConfig;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmRole;
import com.dugnan.moqi.context.StoryContextSnapshot;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 在 Agent Runtime 中生成可恢复的选区讨论或局部正文候选。
 */
@Component
public class SelectionAssistanceWorkflowDefinition implements AgentWorkflowDefinition {

    private static final String ROLE_USER = "user";

    private static final String TEMPLATE_VERSION = "selection-assistance-v3";
    private static final String OPERATION_AUTO = "auto";
    private static final String OPERATION_DISCUSS = "discuss";
    private static final String HIDDEN_REASONING_FIELD = "reasoning";
    private static final String CHAIN_OF_THOUGHT_FIELD = "chainOfThought";
    private static final int MAX_OUTPUT_FIELDS = 4;
    private static final int MAX_PLANNING_PROPOSAL_FIELDS = 4;
    private static final int MAX_RISK_REASONS = 20;
    private final SelectionAssistanceServiceImpl assistanceService;
    private final LlmProviderFactory providerFactory;
    private final UserConfigService userConfigService;
    private final ObjectMapper objectMapper;
    private ProseConversationStream conversationStream;
    private ProseExecutionService executionService;

    @org.springframework.beans.factory.annotation.Autowired
    public void setExecutionService(ProseExecutionService executionService) {
        this.executionService = executionService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setConversationStream(ProseConversationStream conversationStream) {
        this.conversationStream = conversationStream;
    }

    public SelectionAssistanceWorkflowDefinition(
            SelectionAssistanceServiceImpl assistanceService,
            LlmProviderFactory providerFactory,
            UserConfigService userConfigService,
            ObjectMapper objectMapper) {
        this.assistanceService = assistanceService;
        this.providerFactory = providerFactory;
        this.userConfigService = userConfigService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String workflowType() {
        return SelectionAssistanceServiceImpl.WORKFLOW_TYPE;
    }

    @Override
    public String startStepKey() {
        return SelectionAssistanceServiceImpl.GENERATE_STEP;
    }

    @Override
    public Duration timeout() {
        return Duration.ofMinutes(30);
    }

    @Override
    public int maxAttempts(String stepKey) {
        return SelectionAssistanceServiceImpl.GENERATE_STEP.equals(stepKey) ? 3 : 1;
    }

    @Override
    public AgentStepResult execute(String stepKey, AgentStepExecutionContext context) throws Exception {
        if (!SelectionAssistanceServiceImpl.GENERATE_STEP.equals(stepKey)) {
            throw new IllegalArgumentException("未知的选区协助步骤");
        }
        Long assistanceId = assistanceId(context);
        assistanceService.markRunning(assistanceId);
        String operation = assistanceService.operation(assistanceId);
        if (OPERATION_AUTO.equals(operation) && assistanceService.isClarificationSkipped(assistanceId)) {
            assistanceService.completeConversation(assistanceId,
                    "已跳过。正文保持不变，你可以继续讨论或提出新的要求。", false, null);
            return AgentStepResult.completed(Map.of("assistanceId", assistanceId, "operation", operation),
                    Map.of("assistanceId", assistanceId), null);
        }
        LlmExecutionConfig config = userConfigService.requireAvailableExecutionConfig();
        String logicalCallRef = "agent-step:" + context.stepId() + ":selection-assistance";
        LlmProvider provider = providerFactory.createObserved(config,
                LlmCallContext.builder(workflowType(), stepKey)
                        .workId(number(context.input().get("workId")))
                        .aiTaskId(number(context.input().get("aiTaskId")))
                        .agentRunId(context.runId())
                        .agentStepId(context.stepId())
                        .logicalCallId(logicalCallRef)
                        .promptTemplateVersion(TEMPLATE_VERSION)
                        .sourceFingerprint(assistanceService.sourceFingerprint(assistanceId))
                        .build());
        if (OPERATION_AUTO.equals(operation) && assistanceService.isTwoStageConversation(assistanceId)) {
            executionService.generate(assistanceId, provider, logicalCallRef);
            return AgentStepResult.completed(Map.of("assistanceId", assistanceId, "operation", operation),
                    Map.of("assistanceId", assistanceId), null);
        }
        String instruction = systemInstruction(operation);
        if (assistanceService.isObjectConversation(assistanceId)) {
            instruction = "你是作者的正文编辑搭档，这个会话属于当前整篇正文或候选。引用只标明当轮讨论起点，"
                    + "不限制后续讨论或修改范围。根据最近交流理解这段、刚才方案等指代，新的作者要求优先。"
                    + "只输出一个 JSON 对象，且仅有以下字段之一："
                    + "advice（字符串）：评价、解释、自由讨论或先写一版看看，均只在聊天中输出；"
                    + "clarification（字符串）：仅意图或修改对象不清时提问，允许自由回答或跳过；"
                    + "edits（数组）：仅本轮明确授权修改时，返回 [{\"original\":\"最新全文中的精确原文\","
                    + "\"replacement\":\"替换后的正文\"}]，可含多处互不重叠的修改。"
                    + "original 必须逐字复制最新编辑器全文，且能唯一定位；重复文本需包含足够相邻原文。"
                    + "不要返回未修改的整篇全文；仅明确要求全篇重写时才允许整篇替换。空编辑器新增正文时 original 可为空。"
                    + "不改动其他段落，不根据旧引用位置推测当前偏移；无法定位时澄清。"
                    + "跳过不授权修改，历史草稿、助手建议及摘要都不是本轮修改授权或已确认事实。"
                    + "修改只进入编辑器，保存由作者操作；不得宣称已经保存、采纳、发布或更新规划。";
            instruction += "响应形状示例（只能选一种）：{\"advice\":\"讨论内容\"}，"
                    + "{\"clarification\":\"需要澄清的问题\"}，"
                    + "{\"edits\":[{\"original\":\"原文\",\"replacement\":\"新文\"}]}。"
                    + "禁止同时输出多个顶层字段，不输出空占位字段、Markdown 代码围栏或 JSON 外的说明；"
                    + "即使要求简短回答也必须闭合 JSON 字符串和括号。";
        }
        StoryContextSnapshot snapshot = assistanceService.buildModelContext(assistanceId, provider, instruction);
        List<LlmMessage> messages = snapshot == null
                ? legacyMessages(assistanceId, instruction) : snapshot.toMessages();
        if (OPERATION_AUTO.equals(operation)) {
            conversationStream.generate(assistanceId, provider, new LlmRequest(messages,
                    new LlmOptions(snapshot == null ? 4096 : snapshot.outputReserveTokens(),
                            null, List.of(), LlmResponseFormat.JSON_OBJECT)), logicalCallRef);
            return AgentStepResult.completed(Map.of("assistanceId", assistanceId, "operation", operation),
                    Map.of("assistanceId", assistanceId), null);
        }
        LlmResponse response = provider.generate(new LlmRequest(messages,
                new LlmOptions(snapshot == null ? 4096 : snapshot.outputReserveTokens(),
                        null, List.of(), LlmResponseFormat.JSON_OBJECT)));
        ParsedResult parsed = parse(operation, response == null ? null : response.structuredContent());
        assistanceService.complete(assistanceId, parsed.content(), parsed.factRisk(), parsed.reasons(),
                parsed.planningProposal(), logicalCallRef);
        return AgentStepResult.completed(Map.of("assistanceId", assistanceId, "operation", operation),
                Map.of("assistanceId", assistanceId), null);
    }

    private List<LlmMessage> legacyMessages(Long assistanceId, String instruction) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage(LlmRole.SYSTEM, instruction));
        List<ConversationHistoryMessage> history = assistanceService.modelHistory(assistanceId);
        if (history != null) {
            history.forEach(message -> messages.add(new LlmMessage(
                    ROLE_USER.equals(message.role()) ? LlmRole.USER : LlmRole.ASSISTANT,
                    message.content())));
        }
        messages.add(new LlmMessage(LlmRole.USER, assistanceService.modelPrompt(assistanceId)));
        return messages;
    }

    @Override
    public void applyFailure(String stepKey, AgentStepExecutionContext context, Exception exception) {
        assistanceService.fail(assistanceId(context), errorCode(exception));
    }

    @Override
    public String errorCategory(Exception exception) {
        return "provider";
    }

    @Override
    public String errorCode(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProseConversationMemoryService.ContextUnavailableException) {
                return "PROSE_CONTEXT_SUMMARY_FAILED";
            }
        }
        if (exception instanceof BusinessException businessException
                && ErrorCode.AGENT_RUN_TIMED_OUT == businessException.getErrorCode()) {
            return ErrorCode.AGENT_RUN_TIMED_OUT.name();
        }
        return exception instanceof IllegalArgumentException
                ? "SELECTION_ASSISTANCE_INVALID_OUTPUT" : "SELECTION_ASSISTANCE_PROVIDER_FAILED";
    }

    private ParsedResult parse(String operation, JsonNode output) {
        if (output == null || !output.isObject()) {
            throw new IllegalArgumentException("模型未返回 JSON 对象");
        }
        String resultField = OPERATION_DISCUSS.equals(operation) ? "advice" : "replacement";
        JsonNode content = output.get(resultField);
        if (content == null || !content.isTextual()
                || !org.springframework.util.StringUtils.hasText(content.textValue())) {
            throw new IllegalArgumentException("模型未返回有效的局部结果字段");
        }
        Set<String> allowedFields = OPERATION_DISCUSS.equals(operation)
                ? Set.of("advice", "factRisk", "factRiskReasons")
                : Set.of("replacement", "factRisk", "factRiskReasons", "planningProposal");
        boolean hasUnknownField = output.propertyStream()
                .map(Map.Entry::getKey)
                .anyMatch(field -> !allowedFields.contains(field));
        if (output.size() > MAX_OUTPUT_FIELDS || hasUnknownField || output.has(HIDDEN_REASONING_FIELD)
                || output.has(CHAIN_OF_THOUGHT_FIELD)) {
            throw new IllegalArgumentException("模型结果包含契约外字段");
        }
        String risk = output.has("factRisk") && output.get("factRisk").isTextual()
                ? output.get("factRisk").textValue() : "review_required";
        List<String> reasons = new ArrayList<>();
        JsonNode reasonNode = output.get("factRiskReasons");
        if (reasonNode != null) {
            if (!reasonNode.isArray() || reasonNode.size() > MAX_RISK_REASONS) {
                throw new IllegalArgumentException("事实风险理由不符合结构化契约");
            }
            reasonNode.forEach(item -> {
                if (!item.isTextual()) {
                    throw new IllegalArgumentException("事实风险理由必须为字符串");
                }
                reasons.add(item.textValue());
            });
        }
        ModelPlanningProposal planningProposal = parsePlanningProposal(operation, output.get("planningProposal"));
        return new ParsedResult(content.textValue(), risk, List.copyOf(reasons), planningProposal);
    }

    private ModelPlanningProposal parsePlanningProposal(String operation, JsonNode proposalNode) {
        if (proposalNode == null || proposalNode.isNull()) {
            return null;
        }
        if (OPERATION_DISCUSS.equals(operation) || !proposalNode.isObject()
                || proposalNode.size() != MAX_PLANNING_PROPOSAL_FIELDS
                || !proposalNode.has("changeReason") || !proposalNode.has("beforeSummary")
                || !proposalNode.has("afterSummary") || !proposalNode.has("scenes")) {
            throw new IllegalArgumentException("模型规划提案不符合结构化契约");
        }
        try {
            return objectMapper.treeToValue(proposalNode, ModelPlanningProposal.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("模型规划提案无法解析", exception);
        }
    }

    private String systemInstruction(String operation) {
        String historyRule = "此前消息来自当前正文对象自己的会话；历史作者消息用于理解作者意图，"
                + "历史助手回复只是候选建议，除非作者随后明确确认，否则不得当成权威事实。";
        if (OPERATION_AUTO.equals(operation)) {
            return "你是作者的正文编辑搭档。根据当前要求和会话上下文直接行动。只输出一个 JSON 对象且只能有一个字段："
                    + "只有作者明确要求修改当前编辑器正文时，输出 {\"replacement\":\"纯正文改写稿\"}，系统会将结果写入编辑器但不会保存。"
                    + "询问评价、解释、自由讨论，以及先写一版看看、试写、只给建议时，输出 {\"advice\":\"讨论回答或试写正文\"}。"
                    + "不能因为历史里有改写稿而把本轮讨论解释成修改。作者明确要求且范围清楚时不要重复询问。"
                    + "只有修改意图或范围不明时输出 {\"clarification\":\"简短问题，说明可以自由回答或跳过\"}。"
                    + "跳过仅表示继续讨论，不授权修改；用户仅回答跳过时必须输出 advice。"
                    + "replacement 只能包含绑定范围的完整新正文，不含说明、标题、Markdown 围栏或修改理由，"
                    + "保留范围内未要求修改的正文，不得声称已保存或发布。若要求超出绑定范围，用 clarification 澄清。"
                    + "继续调整时依据上轮改写稿与最新要求，但返回完整的范围内正文。不能擅自改变已确认故事事实或规划。"
                    + historyRule;
        }
        if (OPERATION_DISCUSS.equals(operation)) {
            return "只输出 JSON 对象 {\"advice\":\"...\",\"factRisk\":\"safe|review_required\","
                    + "\"factRiskReasons\":[]}。仅给出写作建议，不生成替换正文，不确认任何故事事实。"
                    + historyRule;
        }
        return "只输出 JSON 对象 {\"replacement\":\"...\",\"factRisk\":\"safe|review_required\","
                + "\"factRiskReasons\":[],\"planningProposal\":null}。只改写给定选区，结果始终是待作者应用和保存的候选；"
                + "不得更新规划、知识或发布状态。只有改写确实要求改变当前场景规划时，才把 planningProposal 替换为对象，"
                + "对象必须且只能包含 changeReason、beforeSummary、afterSummary、scenes 四个字段。"
                + "changeReason 说明修改规划的必要性；beforeSummary 必须原样复制输入中的当前规划摘要；"
                + "afterSummary 概括修改后的完整规划；scenes 必须给出修改后的全部场景，不能只给差异。"
                + "规划提案也只是待作者确认的候选，绝不能宣称已经生效。"
                + historyRule;
    }

    private Long assistanceId(AgentStepExecutionContext context) {
        Long value = number(context.input().get("assistanceId"));
        if (value == null) {
            throw new IllegalArgumentException("Agent Run 缺少 assistanceId");
        }
        return value;
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private record ParsedResult(
            String content,
            String factRisk,
            List<String> reasons,
            ModelPlanningProposal planningProposal) {
    }
}
