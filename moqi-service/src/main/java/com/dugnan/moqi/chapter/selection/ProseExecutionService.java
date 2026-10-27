package com.dugnan.moqi.chapter.selection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.dugnan.moqi.chapter.entity.ProseExecutionCallEntity;
import com.dugnan.moqi.chapter.mapper.ProseExecutionCallMapper;
import com.dugnan.moqi.chapter.stream.ChapterReplyEvent;
import com.dugnan.moqi.llm.LlmMessage;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponseFormat;
import com.dugnan.moqi.llm.LlmRole;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamCallRegistry;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamStatus;

/**
 * @author dgn
 * @date 2026-10-25
 * @description 冻结上下文后先识别任务，再交付和校验正文；最多修复一次。
 */
@Service
public class ProseExecutionService {
    private static final String FIELD_TASK = "task";
    private static final String FIELD_SCOPE = "scope";
    private static final String FIELD_QUESTION = "question";
    private static final String FIELD_REPLACEMENT = "replacement";
    private static final String FIELD_TEXT = "text";
    private static final int MAX_EXECUTION_ATTEMPTS = 2;
    private static final double ROUTING_TEMPERATURE = 0D;
    private static final double EXECUTION_TEMPERATURE = 0.4D;
    private static final String DISCUSS = "discuss";
    private static final String DRAFT = "draft";
    private static final String EDIT = "edit";
    private static final String CLARIFY = "clarify";
    private static final String NONE = "none";
    private static final String WHOLE = "whole";
    private static final String PARTIAL = "partial";
    private static final String CURRENT = "current";
    private static final String CHAT = "chat";
    private static final String NO_CHANGE = "no_change";
    private static final String DISCUSSION = "discussion";
    private static final int MAX_OUTPUT_LENGTH = 700000;
    private static final int RESULT_FIELD_COUNT = 2;
    private static final Pattern PROMISE_ONLY = Pattern.compile(
            "^(?:好的[，。]?)?(?:下面|以下)是(?:修改后|重写后|试写)的?正文[：。]?$"
                    + "|^(?:好的[，。]?)?我(?:来|会)(?:修改|重写|试写)(?:一下)?[。！]?$");
    private final SelectionAssistanceServiceImpl assistance;
    private final ProseExecutionStateService state;
    private final ProseExecutionCallMapper calls;
    private final ObjectMapper json;
    private final LlmStreamCallRegistry registry;
    private final ApplicationEventPublisher events;

    public ProseExecutionService(SelectionAssistanceServiceImpl assistance, ProseExecutionStateService state,
            ProseExecutionCallMapper calls, ObjectMapper json, LlmStreamCallRegistry registry,
            ApplicationEventPublisher events) {
        this.assistance = assistance;
        this.state = state;
        this.calls = calls;
        this.json = json;
        this.registry = registry;
        this.events = events;
    }

    public void generate(Long id, LlmProvider provider, String callRef) throws Exception {
        var view = assistance.get(id);
        events.publishEvent(ChapterReplyEvent.started(view.chapterId(), view.aiTaskId(), view.conversationId()));
        phase(view, "planning", "理解要求");
        var snapshot = assistance.buildModelContext(id, provider, ProseExecutionPrompts.PLAN);
        List<LlmMessage> frozen = assistance.isContinuousConversation(id)
                ? currentStageLast(snapshot.toMessages()) : snapshot.toMessages();
        PlanningOutput routing = plan(view, provider, frozen);
        JsonNode plan = routing.plan();
        boolean repairUsed = routing.repaired();
        state.plan(id, plan);
        if (CLARIFY.equals(plan.path(FIELD_TASK).asText())) {
            state.result(id, "clarification");
            assistance.completeExecution(id, "【需要澄清】\n" + plan.path(FIELD_QUESTION).asText(), false, null, callRef);
            completed(view);
            return;
        }
        boolean edit = EDIT.equals(plan.path(FIELD_TASK).asText());
        JsonNode executionPlan = plan;
        List<LlmMessage> execution = frozen.stream().map(message ->
                ProseExecutionPrompts.PLAN.equals(message.content())
                        ? new LlmMessage(LlmRole.SYSTEM, ProseExecutionPrompts.execute(executionPlan)) : message).toList();
        CallOutput previous = null;
        String diagnostic = null;
        for (int attempt = 0; attempt < (repairUsed ? 1 : MAX_EXECUTION_ATTEMPTS); attempt++) {
            state.checkRunning(id);
            phase(view, attempt == 0 ? "executing" : "repairing",
                    attempt > 0 ? "正在校对" : edit ? "修改中" : "回复中");
            publish(view, "reply.reset", "");
            List<LlmMessage> request = new ArrayList<>(execution);
            if (previous != null) {
                request.clear();
                request.add(new LlmMessage(LlmRole.SYSTEM, ProseExecutionPrompts.execute(plan)
                        + "\n这次只修复已有输出的具体校验错误，不重新讨论、不扩大授权；保留原输出中与校验无关的内容和事实。"));
                request.add(new LlmMessage(LlmRole.SYSTEM,
                        "【冻结的当前编辑器全文，唯一定位基准】\n" + view.selectedText()));
                if (!previous.raw().isBlank()) {
                    request.add(new LlmMessage(LlmRole.ASSISTANT, previous.raw()));
                }
                request.add(new LlmMessage(LlmRole.USER, "系统校验未通过，尚未修改正文。这不是作者的新授权。"
                        + "请仅修复以下交付错误，保持既定任务与范围：\n" + diagnostic
                        + "\n上面的当前编辑器全文是唯一定位基准，不得凭聊天试写抄写 original。"));
            }
            CallOutput output = call(view, provider, request, attempt == 0 ? "execution" : "repair",
                    Math.min(8192, provider.capabilities().maxOutputTokens() == null
                            ? 8192 : provider.capabilities().maxOutputTokens()),
                    previous == null ? null : previous.id(), true);
            phase(view, "validating", "校验修改");
            try {
                if (!output.complete()) {
                    throw new IllegalArgumentException("输出截断或未完整结束，必须交付完整结果");
                }
                JsonNode result = parse(output.raw());
                String content = validateResult(plan, result, view.selectedText());
                valid(output.id());
                state.checkRunning(id);
                String kind = result.path("kind").asText();
                state.result(id, kind);
                assistance.completeExecution(id, content, EDIT.equals(kind),
                        EDIT.equals(kind) ? "已生成正文修改。" : null, callRef);
                completed(view);
                return;
            } catch (IllegalArgumentException error) {
                invalid(output.id(), error);
                previous = output;
                diagnostic = error.getMessage();
            }
        }
        phase(view, "failed", "本轮未修改，可重试");
        publish(view, "reply.reset", "");
        throw new IllegalArgumentException("正文修改校验未通过，本轮未修改，可重试");
    }

    private PlanningOutput plan(SelectionAssistanceModels.View view, LlmProvider provider, List<LlmMessage> frozen) throws Exception {
        CallOutput planning = call(view, provider, frozen, "planning", 1800, null, false);
        JsonNode plan;
        boolean repairUsed = false;
        try {
            plan = validatePlanning(planning, view.instruction());
            valid(planning.id());
        } catch (IllegalArgumentException error) {
            invalid(planning.id(), error);
            repairUsed = true;
            phase(view, "repairing", "正在校对");
            List<LlmMessage> corrected = new ArrayList<>(frozen);
            if (!planning.raw().isBlank()) {
                corrected.add(new LlmMessage(LlmRole.ASSISTANT, planning.raw()));
            }
            corrected.add(new LlmMessage(LlmRole.USER, "系统校验任务契约失败，这不是作者新要求。仅修复错误："
                    + error.getMessage() + "。任务字段必须名为 task，不能用 type 替代；不得扩大原作者授权。"));
            CallOutput repaired = call(view, provider, corrected, "planning_repair", 1800, planning.id(), false);
            try {
                plan = validatePlanning(repaired, view.instruction());
                valid(repaired.id());
            } catch (IllegalArgumentException failed) {
                invalid(repaired.id(), failed);
                throw failed;
            }
        }
        return new PlanningOutput(plan, repairUsed);
    }

    private List<LlmMessage> currentStageLast(List<LlmMessage> messages) {
        List<LlmMessage> ordered = new ArrayList<>(messages.stream()
                .filter(message -> !ProseExecutionPrompts.PLAN.equals(message.content())).toList());
        int insertion = ordered.size();
        if (insertion > 0 && ordered.get(insertion - 1).role() == LlmRole.USER) {
            insertion--;
        }
        ordered.add(insertion, new LlmMessage(LlmRole.SYSTEM, ProseExecutionPrompts.PLAN));
        return List.copyOf(ordered);
    }

    private CallOutput call(SelectionAssistanceModels.View view, LlmProvider provider, List<LlmMessage> messages,
            String stage, int tokens, Long repairOf, boolean visible) throws Exception {
        state.checkRunning(view.id());
        LlmOptions options = new LlmOptions(tokens, visible && repairOf == null
                ? EXECUTION_TEMPERATURE : ROUTING_TEMPERATURE, List.of(), LlmResponseFormat.JSON_OBJECT);
        ProseExecutionCallEntity audit = new ProseExecutionCallEntity();
        audit.setAssistanceId(view.id());
        audit.setStage(stage);
        audit.setValidationStatus("pending");
        audit.setMessagesJson(json.writeValueAsString(messages));
        audit.setOptionsJson(json.writeValueAsString(options));
        audit.setRepairOfId(repairOf);
        audit.setDeleted(0);
        audit.setVersion(0);
        calls.insert(audit);
        StringBuilder raw = new StringBuilder();
        StringBuilder sent = new StringBuilder();
        LlmStreamCall call = null;
        try {
            call = provider.stream(new LlmRequest(messages, options), event -> {
                if (event instanceof LlmStreamEvent.TextDelta delta && delta.text() != null) {
                    raw.append(delta.text());
                    if (raw.length() > MAX_OUTPUT_LENGTH) {
                        throw new IllegalArgumentException("模型输出超过审计长度限制");
                    }
                    if (visible && !registry.isCancellationRequested(view.aiTaskId())) {
                        String text = preview(raw.toString());
                        if (text.length() > sent.length()) {
                            String next = text.substring(sent.length());
                            sent.append(next);
                            publish(view, "reply.preview", next);
                        }
                    }
                }
            });
            registry.register(view.aiTaskId(), call);
            var result = call.await();
            audit.setMetadataJson(json.writeValueAsString(result.metadata()));
            boolean complete = result.status() == LlmStreamStatus.COMPLETED
                    && !registry.isCancellationRequested(view.aiTaskId())
                    && (result.metadata() == null || !Set.of("length", "max_tokens")
                            .contains(String.valueOf(result.metadata().finishReason())));
            return new CallOutput(audit.getId(), raw.toString(), complete);
        } catch (Exception error) {
            audit.setValidationError("调用未完成：" + error.getClass().getSimpleName());
            audit.setValidationStatus("failed");
            throw error;
        } finally {
            audit.setRawOutput(raw.toString());
            calls.updateById(audit);
            if (call != null) {
                registry.unregister(view.aiTaskId(), call);
            }
        }
    }

    private JsonNode validatePlanning(CallOutput output, String instruction) {
        if (!output.complete()) {
            throw new IllegalArgumentException("任务判断没有完整结束");
        }
        JsonNode plan = parse(output.raw());
        validatePlan(plan, instruction);
        return plan;
    }

    static String preview(String raw) {
        // Decode only public string values; never stream the original text or routing contract.
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < raw.length()) {
            if (raw.charAt(index) != '"') {
                index++;
                continue;
            }
            int end = stringEnd(raw, index + 1);
            if (end < 0) {
                break;
            }
            String key = raw.substring(index + 1, end);
            index = end + 1;
            while (index < raw.length() && Character.isWhitespace(raw.charAt(index))) {
                index++;
            }
            if (index >= raw.length() || raw.charAt(index) != ':') {
                continue;
            }
            index++;
            while (index < raw.length() && Character.isWhitespace(raw.charAt(index))) {
                index++;
            }
            if (index < raw.length() && raw.charAt(index) == '"') {
                int valueEnd = stringEnd(raw, index + 1);
                if (Set.of(FIELD_TEXT, FIELD_REPLACEMENT).contains(key)) {
                    if (!result.isEmpty()) {
                        result.append("\n\n");
                    }
                    result.append(ProseConversationStream.visibleText("{\"advice\":" + raw.substring(index)));
                }
                if (valueEnd < 0) {
                    break;
                }
                index = valueEnd + 1;
            }
        }
        return result.toString();
    }

    private static int stringEnd(String text, int start) {
        for (int index = start; index < text.length(); index++) {
            if (text.charAt(index) == '\\') {
                index++;
            } else if (text.charAt(index) == '"') {
                return index;
            }
        }
        return -1;
    }

    static void validatePlan(JsonNode plan, String instruction) {
        String task = plan.path(FIELD_TASK).asText();
        if (!plan.isObject() || !Set.of(DISCUSS, DRAFT, EDIT, CLARIFY).contains(task)
                || !Set.of(CURRENT, CHAT).contains(plan.path("target").asText())
                || plan.path("goal").asText().isBlank()) {
            throw new IllegalArgumentException("任务判断缺少明确目标");
        }
        if (EDIT.equals(task)) {
            String authorization = plan.path("authorization").asText();
            if (!Set.of(WHOLE, PARTIAL).contains(plan.path(FIELD_SCOPE).asText())
                    || authorization.isBlank() || !instruction.contains(authorization)) {
                throw new IllegalArgumentException("修改没有本轮作者授权依据");
            }
        } else if (!NONE.equals(plan.path(FIELD_SCOPE).asText())) {
            throw new IllegalArgumentException("非修改任务不得带修改范围");
        }
        if (CLARIFY.equals(task) && plan.path(FIELD_QUESTION).asText().isBlank()) {
            throw new IllegalArgumentException("澄清缺少问题");
        }
    }

    static String validateResult(JsonNode plan, JsonNode result, String source) {
        String kind = result.path("kind").asText();
        String task = plan.path(FIELD_TASK).asText();
        if (!result.isObject() || result.size() != RESULT_FIELD_COUNT) {
            throw new IllegalArgumentException("交付必须有 kind 及内容两个字段；缺少 kind 时请补齐本轮对应值 discussion、draft、edit 或 no_change，禁止使用 type 替代 kind");
        }
        if (EDIT.equals(task) && EDIT.equals(kind)) {
            String updated;
            if (WHOLE.equals(plan.path(FIELD_SCOPE).asText())) {
                if (!result.path(FIELD_REPLACEMENT).isTextual() || result.path(FIELD_REPLACEMENT).asText().isBlank()) {
                    throw new IllegalArgumentException("全篇重写必须交付完整新正文");
                }
                updated = result.path(FIELD_REPLACEMENT).asText();
            } else {
                updated = ProseConversationProtocol.apply(source, result.get("edits"));
            }
            if (source.equals(updated)) {
                throw new IllegalArgumentException("修改内容与原文相同；若无需修改必须说明具体核对依据");
            }
            return updated;
        }
        String expected = EDIT.equals(task) ? NO_CHANGE : DRAFT.equals(task) ? DRAFT : DISCUSSION;
        if (!expected.equals(kind) || !result.path(FIELD_TEXT).isTextual() || result.path(FIELD_TEXT).asText().isBlank()) {
            throw new IllegalArgumentException("本轮要求" + task + "，返回类型不符或没有实际交付内容");
        }
        String text = result.path(FIELD_TEXT).asText();
        if (DRAFT.equals(kind) && PROMISE_ONLY.matcher(text.strip()).matches()) {
            throw new IllegalArgumentException("试写只包含承诺或说明，必须给出实际正文");
        }
        return text;
    }

    private JsonNode parse(String raw) {
        try {
            JsonNode node = json.reader().with(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if (node == null) {
                throw new IllegalArgumentException("结果为空");
            }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("输出 JSON 不完整或包含重复字段，请重新输出完整结果", error);
        }
    }

    private void invalid(Long id, Exception error) {
        ProseExecutionCallEntity patch = new ProseExecutionCallEntity();
        patch.setId(id);
        patch.setValidationError(error.getMessage());
        patch.setValidationStatus("failed");
        calls.updateById(patch);
    }

    private void valid(Long id) {
        ProseExecutionCallEntity patch = new ProseExecutionCallEntity();
        patch.setId(id);
        patch.setValidationStatus("passed");
        calls.updateById(patch);
    }

    private void phase(SelectionAssistanceModels.View view, String stage, String label) {
        state.stage(view.id(), stage);
        publish(view, "reply.stage", label);
    }

    private void publish(SelectionAssistanceModels.View view, String type, String text) {
        events.publishEvent(new ChapterReplyEvent(type, view.chapterId(), view.aiTaskId(), null,
                text, null, null, null, view.conversationId()));
    }

    private void completed(SelectionAssistanceModels.View view) {
        var finished = assistance.get(view.id());
        events.publishEvent(ChapterReplyEvent.completed(view.chapterId(), view.aiTaskId(),
                view.conversationId(), finished.assistantMessageId()));
    }

    private record PlanningOutput(JsonNode plan, boolean repaired) {
    }

    private record CallOutput(Long id, String raw, boolean complete) {
    }
}
