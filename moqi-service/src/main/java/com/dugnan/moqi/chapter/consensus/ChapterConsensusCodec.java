package com.dugnan.moqi.chapter.consensus;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 负责章节结构化共识与 Brief JSON 文本之间的兼容转换。
 */
@Component
public class ChapterConsensusCodec {

    private static final int SCHEMA_VERSION = 1;
    private static final String FIELD_SCHEMA_VERSION = "schemaVersion";

    private static final int MAX_SERIALIZED_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;

    private final ChapterConsensusValidator validator;

    /**
     * 创建章节共识编解码器。
     *
     * @param objectMapper JSON 映射器
     * @param validator 共识校验器
     */
    public ChapterConsensusCodec(ObjectMapper objectMapper, ChapterConsensusValidator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /**
     * 将结构化共识序列化为可持久化 JSON。
     *
     * @param content 原始共识
     * @return 规范化后的 JSON
     */
    public String write(ChapterConsensusContentV1 content) {
        ChapterConsensusContentV1 normalized = validator.normalizeDraft(content);
        try {
            String json = objectMapper.writeValueAsString(normalized);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SERIALIZED_BYTES) {
                throw new BusinessException(
                        ErrorCode.CHAPTER_CONSENSUS_INVALID,
                        "章节共识序列化后不能超过 " + MAX_SERIALIZED_BYTES + " 字节");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_CONSENSUS_INVALID,
                    "章节共识无法序列化",
                    exception);
        }
    }

    /**
     * 从 Brief 内容读取结构化共识；历史文本或不可识别内容明确降级为文本。
     *
     * @param briefContent Brief 持久化内容
     * @return 兼容文档
     */
    public ChapterConsensusDocument read(String briefContent) {
        if (briefContent == null || briefContent.isBlank()) {
            return ChapterConsensusDocument.legacy(briefContent);
        }
        try {
            JsonNode root = objectMapper.readTree(briefContent);
            if (!root.isObject() || root.path(FIELD_SCHEMA_VERSION).asInt(-1) != SCHEMA_VERSION) {
                return ChapterConsensusDocument.legacy(briefContent);
            }
            ChapterConsensusContentV1 content =
                    objectMapper.treeToValue(root, ChapterConsensusContentV1.class);
            return ChapterConsensusDocument.structured(validator.normalizeDraft(content));
        } catch (JsonProcessingException | BusinessException exception) {
            return ChapterConsensusDocument.legacy(briefContent);
        }
    }
}
