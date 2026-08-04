package com.dugnan.moqi.chapter.consensus;

import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * @author dgn
 * @date 2026-08-02
 * @description 校验并解析章节共识 Provider JSON 的字段与基础类型。
 */
@Component
public class ChapterConsensusResponseParser {

    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion",
            "chapterTask",
            "stateChange",
            "keyPush",
            "readerProgress",
            "writingBoundaries",
            "decisions", "scopeCandidates");

    private static final Set<String> STATE_CHANGE_FIELDS = Set.of("from", "to");

    private static final Set<String> READER_PROGRESS_FIELDS = Set.of("payoff", "openQuestion");

    private static final Set<String> DECISION_FIELDS = Set.of(
            "key",
            "title",
            "status",
            "required",
            "prompt",
            "candidateSummary",
            "sourceMessageIds",
            "sourceQuotes");

    private static final Set<String> SOURCE_QUOTE_FIELDS = Set.of("messageId", "quote");

    private final ObjectMapper objectMapper;

    /**
     * 创建章节共识 JSON 解析器。
     *
     * @param objectMapper JSON 映射器
     */
    public ChapterConsensusResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验必填字段、字段类型和未知字段后转换为领域契约。
     *
     * @param root Provider 返回的结构化 JSON
     * @return 章节共识内容
     */
    public ChapterConsensusContentV1 parse(JsonNode root) {
        requireRoot(root);
        requireIntegral(root.get("schemaVersion"));
        requireText(root.get("chapterTask"));
        requireTextObject(root.get("stateChange"), STATE_CHANGE_FIELDS);
        requireText(root.get("keyPush"));
        requireTextObject(root.get("readerProgress"), READER_PROGRESS_FIELDS);
        requireTextArray(root.get("writingBoundaries"));
        requireDecisions(root.get("decisions"));
        if (root.has("scopeCandidates")) { requireScopeCandidates(root.get("scopeCandidates")); }
        try {
            return objectMapper.treeToValue(root, ChapterConsensusContentV1.class);
        } catch (JsonProcessingException exception) {
            throw new ChapterConsensusJsonException(exception);
        }
    }

    private void requireRoot(JsonNode node) {
        Set<String> required = Set.of("schemaVersion", "chapterTask", "stateChange", "keyPush", "readerProgress", "writingBoundaries", "decisions");
        if (node == null || !node.isObject() || !required.stream().allMatch(node::has)) { throw new ChapterConsensusJsonException(); }
        node.fieldNames().forEachRemaining(field -> { if (!ROOT_FIELDS.contains(field)) { throw new ChapterConsensusJsonException(); } });
    }

    private void requireObject(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size() || !fields.stream().allMatch(node::has)) {
            throw new ChapterConsensusJsonException();
        }
    }

    private void requireScopeCandidates(JsonNode node) {
        if (node == null || !node.isArray()) { throw new ChapterConsensusJsonException(); }
        for (JsonNode value : node) {
            if (!value.has("scope") || !value.has("content") || !value.has("sourceMessageIds") || !value.has("confidence")
                    || !value.get("scope").isTextual() || !value.get("content").isTextual() || !value.get("sourceMessageIds").isArray()
                    || !value.get("confidence").isNumber()) { throw new ChapterConsensusJsonException(); }
        }
    }

    private void requireTextObject(JsonNode node, Set<String> fields) {
        requireObject(node, fields);
        for (String field : fields) {
            requireText(node.get(field));
        }
    }

    private void requireIntegral(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            throw new ChapterConsensusJsonException();
        }
    }

    private void requireText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new ChapterConsensusJsonException();
        }
    }

    private void requireTextArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new ChapterConsensusJsonException();
        }
        node.forEach(this::requireText);
    }

    private void requireDecisions(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new ChapterConsensusJsonException();
        }
        for (JsonNode decision : node) {
            requireObject(decision, DECISION_FIELDS);
            requireText(decision.get("key"));
            requireText(decision.get("title"));
            requireText(decision.get("status"));
            if (!decision.get("required").isBoolean()) {
                throw new ChapterConsensusJsonException();
            }
            requireText(decision.get("prompt"));
            requireText(decision.get("candidateSummary"));
            JsonNode sourceIds = decision.get("sourceMessageIds");
            if (!sourceIds.isArray()) {
                throw new ChapterConsensusJsonException();
            }
            sourceIds.forEach(this::requireIntegral);
            requireSourceQuotes(decision.get("sourceQuotes"));
        }
    }

    private void requireSourceQuotes(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new ChapterConsensusJsonException();
        }
        for (JsonNode sourceQuote : node) {
            requireObject(sourceQuote, SOURCE_QUOTE_FIELDS);
            requireIntegral(sourceQuote.get("messageId"));
            requireText(sourceQuote.get("quote"));
        }
    }
}
