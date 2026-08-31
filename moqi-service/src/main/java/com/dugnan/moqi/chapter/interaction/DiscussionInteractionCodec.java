package com.dugnan.moqi.chapter.interaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageInteraction;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageInteractionOption;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageInteractionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author dgn
 * @date 2026-08-13
 * @description 解析并校验章节讨论结构化交互，失败时安全降级为普通文本。
 */
public final class DiscussionInteractionCodec {

    public static final int SCHEMA_VERSION = 1;
    private static final String TYPE_SINGLE_CHOICE = "single_choice";
    private static final String TYPE_OPEN_QUESTION = "open_question";
    private static final int MAX_IDENTIFIER_LENGTH = 120;
    private static final int MAX_QUESTION_LENGTH = 500;
    private static final int MAX_OPTION_TEXT_LENGTH = 500;
    private static final int MAX_CUSTOM_TEXT_LENGTH = 1000;
    private static final int MIN_CHOICE_OPTIONS = 2;
    private static final int MAX_CHOICE_OPTIONS = 5;
    private static final String DRAFT_REPLY_FIELD = "回复";
    private static final String DRAFT_QUESTION_FIELD = "问题";
    private static final String DRAFT_OPTIONS_FIELD = "选项";
    private static final String DRAFT_TITLE_FIELD = "标题";
    private static final String DRAFT_DESCRIPTION_FIELD = "说明";
    private static final String DRAFT_TRADEOFFS_FIELD = "取舍";
    private static final String DEGRADATION_INVALID_SEMANTIC_STRUCTURE = "invalid_semantic_structure";
    private static final String DEGRADATION_OPTION_COUNT_OUT_OF_RANGE = "option_count_out_of_range";
    private static final String DEGRADATION_NON_JSON_READABLE_TEXT = "non_json_readable_text";
    private static final Set<String> TYPES = Set.of(TYPE_SINGLE_CHOICE, TYPE_OPEN_QUESTION);

    private DiscussionInteractionCodec() {
    }

    public static AssistantResult parseAssistantEnvelope(String raw, ObjectMapper mapper) {
        if (!StringUtils.hasText(raw)) {
            return new AssistantResult("", null, null);
        }
        try {
            JsonNode root = mapper.readTree(raw);
            String content = text(root, "content");
            JsonNode interactionNode = root.get("interaction");
            if (!StringUtils.hasText(content) || interactionNode == null || interactionNode.isNull()) {
                return new AssistantResult(fallbackContent(root, raw), null, null);
            }
            try {
                MessageInteraction interaction = mapper.treeToValue(interactionNode, MessageInteraction.class);
                validate(interaction);
                return new AssistantResult(content.trim(), interaction, mapper.writeValueAsString(interaction));
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                return new AssistantResult(fallbackContent(root, raw), null, null);
            }
        } catch (JsonProcessingException exception) {
            return new AssistantResult(raw.trim(), null, null);
        }
    }

    /**
     * 将模型生成的自然中文比较草稿转换为服务端拥有的交互协议。
     *
     * @param raw 模型返回的中文字段 JSON
     * @param mapper JSON 映射器
     * @param questionId 服务端生成的稳定问题标识
     * @return 可读正文和经校验的单选交互
     */
    public static AssistantResult parseComparisonDraft(
            String raw,
            ObjectMapper mapper,
            String questionId) {
        try {
            JsonNode root = mapper.readTree(raw);
            String content = text(root, DRAFT_REPLY_FIELD);
            String question = text(root, DRAFT_QUESTION_FIELD);
            JsonNode optionsNode = root == null ? null : root.get(DRAFT_OPTIONS_FIELD);
            if (!StringUtils.hasText(content) || !StringUtils.hasText(question)) {
                return semanticFallback(root, DEGRADATION_INVALID_SEMANTIC_STRUCTURE);
            }
            if (hasNoOptions(optionsNode)) {
                return clarificationResult(content, question, questionId, mapper);
            }
            if (!optionsNode.isArray()
                    || optionsNode.size() < MIN_CHOICE_OPTIONS
                    || optionsNode.size() > MAX_CHOICE_OPTIONS) {
                return semanticFallback(root, DEGRADATION_OPTION_COUNT_OUT_OF_RANGE);
            }
            List<MessageInteractionOption> options = new ArrayList<>();
            for (int index = 0; index < optionsNode.size(); index++) {
                JsonNode option = optionsNode.get(index);
                options.add(new MessageInteractionOption(
                        "option-" + (index + 1),
                        text(option, DRAFT_TITLE_FIELD),
                        text(option, DRAFT_DESCRIPTION_FIELD),
                        text(option, DRAFT_TRADEOFFS_FIELD)));
            }
            MessageInteraction interaction = new MessageInteraction(
                    SCHEMA_VERSION,
                    TYPE_SINGLE_CHOICE,
                    questionId,
                    question,
                    options,
                    true);
            validate(interaction);
            return new AssistantResult(
                    content.trim(), interaction, mapper.writeValueAsString(interaction));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return recoverReadableTextOrThrow(raw, mapper, exception);
        }
    }

    /**
     * 保留旧调用签名，V5 不再使用预计算的比较对象数量。
     *
     * @param raw 模型返回的中文字段 JSON
     * @param mapper JSON 映射器
     * @param questionId 服务端生成的问题标识
     * @param ignoredExpectedOptionCount 已废弃的预计算数量
     * @return 可读正文和可选交互
     */
    public static AssistantResult parseComparisonDraft(
            String raw,
            ObjectMapper mapper,
            String questionId,
            int ignoredExpectedOptionCount) {
        return parseComparisonDraft(raw, mapper, questionId);
    }

    /**
     * 将模型生成的自然中文澄清草稿转换为服务端拥有的交互协议。
     *
     * @param raw 模型返回的中文字段 JSON
     * @param mapper JSON 映射器
     * @param questionId 服务端生成的稳定问题标识
     * @return 可读正文和经校验的开放问题交互
     */
    public static AssistantResult parseClarificationDraft(
            String raw,
            ObjectMapper mapper,
            String questionId) {
        try {
            JsonNode root = mapper.readTree(raw);
            String content = text(root, DRAFT_REPLY_FIELD);
            String question = text(root, DRAFT_QUESTION_FIELD);
            if (!StringUtils.hasText(content) || !StringUtils.hasText(question)) {
                return semanticFallback(root, DEGRADATION_INVALID_SEMANTIC_STRUCTURE);
            }
            return clarificationResult(content, question, questionId, mapper);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return recoverReadableTextOrThrow(raw, mapper, exception);
        }
    }

    public static boolean isOpenQuestion(String interactionJson, ObjectMapper mapper) {
        MessageInteraction interaction = parseInteraction(interactionJson, mapper);
        return interaction != null && TYPE_OPEN_QUESTION.equals(interaction.type());
    }

    public static MessageInteraction parseInteraction(String json, ObjectMapper mapper) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            MessageInteraction interaction = mapper.readValue(json, MessageInteraction.class);
            validate(interaction);
            return interaction;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return null;
        }
    }

    public static MessageInteractionResponse parseResponse(String json, ObjectMapper mapper) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            MessageInteractionResponse response = mapper.readValue(json, MessageInteractionResponse.class);
            String customText = trim(response.customText());
            if (isInvalidStoredResponse(response, customText)) {
                return null;
            }
            return response;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    public static ValidatedResponse validateResponse(
            MessageInteraction interaction,
            MessageInteractionResponse response) {
        if (interaction == null || response == null || response.schemaVersion() != SCHEMA_VERSION
                || !interaction.questionId().equals(response.questionId())) {
            throw new IllegalArgumentException("结构化回答与来源问题不一致");
        }
        String customText = trim(response.customText());
        if (customText != null && customText.length() > MAX_CUSTOM_TEXT_LENGTH) {
            throw new IllegalArgumentException("补充说明不能超过 1000 个字符");
        }
        MessageInteractionOption selected = null;
        if (StringUtils.hasText(response.optionId())) {
            selected = interaction.options().stream()
                    .filter(option -> option.optionId().equals(response.optionId().trim()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("选项不属于来源问题"));
        }
        if (TYPE_SINGLE_CHOICE.equals(interaction.type()) && selected == null && customText == null) {
            throw new IllegalArgumentException("请选择一个选项或填写自己的描述");
        }
        if (TYPE_OPEN_QUESTION.equals(interaction.type()) && customText == null) {
            throw new IllegalArgumentException("请填写回答内容");
        }
        if (customText != null && !interaction.allowCustom()) {
            throw new IllegalArgumentException("该问题不允许自定义回答");
        }
        String content = selected == null ? customText : "我选择“" + selected.title() + "”"
                + (customText == null ? "。" : "。补充：" + customText);
        return new ValidatedResponse(
                content,
                new MessageInteractionResponse(SCHEMA_VERSION, interaction.questionId(),
                        selected == null ? null : selected.optionId(), customText));
    }

    public static String serializeResponse(MessageInteractionResponse response, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("结构化回答无法序列化", exception);
        }
    }

    public static void validate(MessageInteraction interaction) {
        if (isInvalidInteractionHeader(interaction)) {
            throw new IllegalArgumentException("结构化问题版本或类型不受支持");
        }
        requireText(interaction.questionId(), MAX_IDENTIFIER_LENGTH, "questionId");
        requireText(interaction.question(), MAX_QUESTION_LENGTH, "question");
        List<MessageInteractionOption> options = interaction.options() == null ? List.of() : interaction.options();
        if (hasInvalidChoiceOptionCount(interaction.type(), options.size())) {
            throw new IllegalArgumentException("单选题必须包含 2 至 5 个选项");
        }
        if (TYPE_OPEN_QUESTION.equals(interaction.type()) && !options.isEmpty()) {
            throw new IllegalArgumentException("开放问题不能包含选项");
        }
        Set<String> optionIds = new HashSet<>();
        for (MessageInteractionOption option : options) {
            requireText(option.optionId(), MAX_IDENTIFIER_LENGTH, "optionId");
            requireText(option.title(), MAX_IDENTIFIER_LENGTH, "title");
            optionalText(option.description(), MAX_OPTION_TEXT_LENGTH, "description");
            optionalText(option.tradeoffs(), MAX_OPTION_TEXT_LENGTH, "tradeoffs");
            if (!optionIds.add(option.optionId().trim())) {
                throw new IllegalArgumentException("选项 ID 不能重复");
            }
        }
    }

    private static boolean isInvalidStoredResponse(MessageInteractionResponse response, String customText) {
        return response.schemaVersion() != SCHEMA_VERSION
                || !StringUtils.hasText(response.questionId())
                || response.questionId().trim().length() > MAX_IDENTIFIER_LENGTH
                || (customText != null && customText.length() > MAX_CUSTOM_TEXT_LENGTH);
    }

    private static boolean isInvalidInteractionHeader(MessageInteraction interaction) {
        return interaction == null
                || interaction.schemaVersion() != SCHEMA_VERSION
                || !TYPES.contains(interaction.type());
    }

    private static boolean hasInvalidChoiceOptionCount(String type, int optionCount) {
        if (!TYPE_SINGLE_CHOICE.equals(type)) {
            return false;
        }
        return optionCount < MIN_CHOICE_OPTIONS || optionCount > MAX_CHOICE_OPTIONS;
    }

    private static String fallbackContent(JsonNode root, String raw) {
        String content = text(root, "content");
        return StringUtils.hasText(content) ? content.trim() : raw.trim();
    }

    private static AssistantResult clarificationResult(
            String content,
            String question,
            String questionId,
            ObjectMapper mapper) throws JsonProcessingException {
        MessageInteraction interaction = new MessageInteraction(
                SCHEMA_VERSION,
                TYPE_OPEN_QUESTION,
                questionId,
                question,
                List.of(),
                true);
        validate(interaction);
        return new AssistantResult(content.trim(), interaction, mapper.writeValueAsString(interaction));
    }

    private static boolean hasNoOptions(JsonNode optionsNode) {
        if (optionsNode == null || optionsNode.isNull()) {
            return true;
        }
        return optionsNode.isArray() && optionsNode.isEmpty();
    }

    private static AssistantResult semanticFallback(JsonNode root, String reason) {
        String readable = readableSemantics(root);
        if (!StringUtils.hasText(readable)) {
            throw new StructuredOutputException("模型结构化回复没有可恢复的可读内容");
        }
        return new AssistantResult(readable, null, null, reason);
    }

    private static AssistantResult recoverReadableTextOrThrow(
            String raw,
            ObjectMapper mapper,
            Exception cause) {
        try {
            return semanticFallback(mapper.readTree(raw), DEGRADATION_INVALID_SEMANTIC_STRUCTURE);
        } catch (JsonProcessingException exception) {
            String normalized = raw == null ? "" : raw.trim();
            if (StringUtils.hasText(normalized) && !looksLikeJson(normalized)) {
                return new AssistantResult(normalized, null, null, DEGRADATION_NON_JSON_READABLE_TEXT);
            }
            throw new StructuredOutputException("模型结构化回复无法解析", cause);
        }
    }

    private static String readableSemantics(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        List<String> sections = new ArrayList<>();
        addReadableSection(sections, text(root, DRAFT_REPLY_FIELD));
        addReadableSection(sections, text(root, DRAFT_QUESTION_FIELD));
        JsonNode options = root.get(DRAFT_OPTIONS_FIELD);
        if (options != null && options.isArray()) {
            for (int index = 0; index < options.size(); index++) {
                JsonNode option = options.get(index);
                List<String> optionLines = new ArrayList<>();
                String title = text(option, DRAFT_TITLE_FIELD);
                if (StringUtils.hasText(title)) {
                    optionLines.add((index + 1) + ". " + title.trim());
                }
                addReadableSection(optionLines, text(option, DRAFT_DESCRIPTION_FIELD));
                String tradeoffs = text(option, DRAFT_TRADEOFFS_FIELD);
                if (StringUtils.hasText(tradeoffs)) {
                    optionLines.add("取舍：" + tradeoffs.trim());
                }
                if (!optionLines.isEmpty()) {
                    sections.add(String.join("\n", optionLines));
                }
            }
        }
        return sections.isEmpty() ? null : String.join("\n\n", sections);
    }

    private static void addReadableSection(List<String> sections, String value) {
        if (StringUtils.hasText(value)) {
            sections.add(value.trim());
        }
    }

    private static boolean looksLikeJson(String value) {
        return value.startsWith("{") || value.startsWith("[")
                || value.contains("\"" + DRAFT_REPLY_FIELD + "\"")
                || value.contains("\"" + DRAFT_OPTIONS_FIELD + "\"");
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static void requireText(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value) || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(field + " 不能为空且不能超过 " + maxLength + " 个字符");
        }
    }

    private static void optionalText(String value, int maxLength, String field) {
        if (value != null && value.trim().length() > maxLength) {
            throw new IllegalArgumentException(field + " 不能超过 " + maxLength + " 个字符");
        }
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record AssistantResult(
            String content,
            MessageInteraction interaction,
            String interactionJson,
            String degradationReason) {

        public AssistantResult(String content, MessageInteraction interaction, String interactionJson) {
            this(content, interaction, interactionJson, null);
        }
    }

    public record ValidatedResponse(String content, MessageInteractionResponse response) {
    }

    /** 表示模型结构化结果无法安全转换为可读回复。 */
    public static final class StructuredOutputException extends RuntimeException {

        public StructuredOutputException(String message) {
            super(message);
        }

        public StructuredOutputException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
