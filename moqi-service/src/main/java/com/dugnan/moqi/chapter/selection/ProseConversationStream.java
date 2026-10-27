package com.dugnan.moqi.chapter.selection;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.chapter.stream.ChapterReplyEvent;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmStreamCall;
import com.dugnan.moqi.llm.LlmStreamCallRegistry;
import com.dugnan.moqi.llm.LlmStreamEvent;
import com.dugnan.moqi.llm.LlmStreamResult;
import com.dugnan.moqi.llm.LlmStreamStatus;

/**
 * @author dgn
 * @date 2026-10-22
 * @description 流式呈现正文对话的回答或改写稿，完整校验后才建立可应用结果。
 */
@Component
public class ProseConversationStream {
    private static final Pattern CONTENT_START = Pattern.compile("^\\s*\\{\\s*\"(advice|replacement|clarification)\"\\s*:\\s*\"");
    private static final String EDITS = "edits";
    private static final String REPLACEMENT = "replacement";
    private static final int MAX_RESPONSE_LENGTH = 700000;
    private static final java.util.Set<String> OUTPUT_FIELDS = java.util.Set.of("advice", "replacement", "clarification", "edits");
    private final SelectionAssistanceServiceImpl assistanceService;
    private final ApplicationEventPublisher publisher;
    private final LlmStreamCallRegistry registry;
    private final ObjectMapper objectMapper;

    public ProseConversationStream(SelectionAssistanceServiceImpl assistanceService,
            ApplicationEventPublisher publisher, LlmStreamCallRegistry registry, ObjectMapper objectMapper) {
        this.assistanceService = assistanceService;
        this.publisher = publisher;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    public void generate(Long assistanceId, LlmProvider provider, LlmRequest request, String callRef) throws Exception {
        SelectionAssistanceModels.View view = assistanceService.get(assistanceId);
        StringBuilder response = new StringBuilder();
        StringBuilder published = new StringBuilder();
        publisher.publishEvent(ChapterReplyEvent.started(view.chapterId(), view.aiTaskId(), view.conversationId()));
        LlmStreamCall call = provider.stream(request, event -> {
            if (event instanceof LlmStreamEvent.TextDelta delta && delta.text() != null
                    && !registry.isCancellationRequested(view.aiTaskId())) {
                response.append(delta.text());
                if (response.length() > MAX_RESPONSE_LENGTH) {
                    throw new IllegalArgumentException("正文对话输出过长");
                }
                String visible = visibleText(response.toString());
                if (visible.length() > published.length()) {
                    String next = visible.substring(published.length());
                    published.append(next);
                    publisher.publishEvent(ChapterReplyEvent.delta(
                            view.chapterId(), view.aiTaskId(), view.conversationId(), next));
                }
            }
        });
        registry.register(view.aiTaskId(), call);
        try {
            LlmStreamResult result = call.await();
            String finish = result.metadata() == null ? null : result.metadata().finishReason();
            if (result.status() != LlmStreamStatus.COMPLETED || registry.isCancellationRequested(view.aiTaskId())
                    || "length".equalsIgnoreCase(finish) || "max_tokens".equalsIgnoreCase(finish)) {
                throw new IllegalArgumentException("正文对话未完整生成");
            }
            // 部分 Provider 在 JSON 字符串内返回原始换行；仅放宽控制字符，不接受截断或多余字段。
            JsonNode output = objectMapper.reader()
                    .with(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(response.toString());
            if (output == null || !output.isObject() || output.size() != 1) {
                throw new IllegalArgumentException("正文对话结果必须只有一个输出字段");
            }
            if (!OUTPUT_FIELDS.contains(output.fieldNames().next())) {
                throw new IllegalArgumentException("正文对话结果必须区分建议与改写稿");
            }
            if (output.has(EDITS)) {
                assistanceService.completeConversationEdits(assistanceId, output.get(EDITS), callRef);
            } else {
                if (output.has(REPLACEMENT) && assistanceService.isObjectConversation(assistanceId)) {
                    throw new IllegalArgumentException("正文会话修改必须明确原文与新文");
                }
                JsonNode text = output.elements().next();
                if (!text.isTextual() || text.textValue().isBlank()) {
                    throw new IllegalArgumentException("正文对话结果为空");
                }
                assistanceService.completeConversation(assistanceId,
                        output.has("clarification") ? "【需要澄清】\n" + text.textValue() : text.textValue(),
                        output.has(REPLACEMENT), callRef);
            }
            SelectionAssistanceModels.View completed = assistanceService.get(assistanceId);
            publisher.publishEvent(ChapterReplyEvent.completed(view.chapterId(), view.aiTaskId(),
                    view.conversationId(), completed.assistantMessageId()));
        } finally {
            registry.unregister(view.aiTaskId(), call);
        }
    }

    /** 只解码首个正文字符串，未完成的转义序列等待下一块，不暴露 JSON 或其他字段。 */
    static String visibleText(String json) {
        Matcher start = CONTENT_START.matcher(json);
        if (!start.find()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int index = start.end(); index < json.length(); index++) {
            char value = json.charAt(index);
            if (value == '"') {
                break;
            }
            if (value == '\\') {
                if (++index >= json.length()) {
                    break;
                }
                char escape = json.charAt(index);
                if (escape == 'u') {
                    if (index + 4 >= json.length()) {
                        break;
                    }
                    try {
                        text.append((char) Integer.parseInt(json.substring(index + 1, index + 5), 16));
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("正文对话包含无效转义", exception);
                    }
                    index += 4;
                } else {
                    text.append(switch (escape) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case '"', '\\', '/' -> escape;
                        default -> throw new IllegalArgumentException("正文对话包含无效转义");
                    });
                }
            } else {
                text.append(value);
            }
        }
        if (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
            text.setLength(text.length() - 1);
        }
        return text.toString();
    }
}
