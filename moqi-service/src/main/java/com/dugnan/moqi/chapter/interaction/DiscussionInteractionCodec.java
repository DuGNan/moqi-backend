package com.dugnan.moqi.chapter.interaction;

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
    private static final int MAX_CHOICE_OPTIONS = 4;
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
            throw new IllegalArgumentException("单选题必须包含 2 至 4 个选项");
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

    public record AssistantResult(String content, MessageInteraction interaction, String interactionJson) {
    }

    public record ValidatedResponse(String content, MessageInteractionResponse response) {
    }
}
