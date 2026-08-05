package com.dugnan.moqi.chapter.outline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Beat;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Scene;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 读取 V1 章纲并投影为 V2 节拍，且只序列化经过校验的 V2 内容。
 */
@Component
public class OutlineCandidateContentCodec {
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final int MAX_BEAT_COUNT = 50;
    private static final int MAX_BEAT_KEY_LENGTH = 128;
    private static final int MAX_BEAT_SUMMARY_LENGTH = 4000;
    private static final int MAX_CONSTRAINT_COUNT = 50;
    private static final int MAX_CONSTRAINT_LENGTH = 500;

    private final ObjectMapper objectMapper;

    public OutlineCandidateContentCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutlineCandidateContent read(String json) {
        if (!StringUtils.hasText(json)) {
            throw invalid("候选大纲内容不能为空");
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return isVersionTwo(node)
                    ? normalize(objectMapper.treeToValue(node, OutlineCandidateContent.class)) : projectV1(node);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_INVALID, "候选大纲 JSON 无法读取", exception);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_INVALID, "候选大纲 JSON 字段类型无效", exception);
        }
    }

    public String write(OutlineCandidateContent content) {
        try {
            return objectMapper.writeValueAsString(normalize(content));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "候选大纲无法序列化", exception);
        }
    }

    public OutlineCandidateContent normalize(OutlineCandidateContent content) {
        if (content == null) {
            throw invalid("候选大纲不能为空");
        }
        return new OutlineCandidateContent(OutlineCandidateContent.SCHEMA_VERSION,
                optional(content.chapterPurpose(), "chapterPurpose", MAX_TEXT_LENGTH),
                optional(content.openingState(), "openingState", MAX_TEXT_LENGTH),
                required(content.chapterGoal(), "chapterGoal", MAX_TEXT_LENGTH),
                required(content.coreConflict(), "coreConflict", MAX_TEXT_LENGTH),
                beats(content.beats()), optional(content.turningPoint(), "turningPoint", MAX_TEXT_LENGTH),
                optional(content.endingState(), "endingState", MAX_TEXT_LENGTH),
                optional(content.endingHook(), "endingHook", MAX_TEXT_LENGTH), constraints(content.constraints()));
    }

    private OutlineCandidateContent projectV1(JsonNode node) throws JsonProcessingException {
        String goal = node.path("goal").asText(null);
        String conflict = node.path("coreConflict").asText(null);
        List<Beat> beats = new ArrayList<>();
        JsonNode scenes = node.path("scenes");
        if (scenes.isArray()) {
            for (JsonNode sceneNode : scenes) {
                Scene scene = objectMapper.treeToValue(sceneNode, Scene.class);
                beats.add(scene.toBeat());
            }
        }
        List<String> constraints = node.has("constraints")
                ? objectMapper.convertValue(node.get("constraints"), objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, String.class)) : List.of();
        return normalize(new OutlineCandidateContent(OutlineCandidateContent.SCHEMA_VERSION, null, null, goal, conflict,
                beats, null, null, null, constraints));
    }

    private boolean isVersionTwo(JsonNode node) {
        JsonNode schemaVersion = node.get("schemaVersion");
        if (schemaVersion != null) {
            return schemaVersion.asInt(1) >= OutlineCandidateContent.SCHEMA_VERSION;
        }
        return node.has("chapterGoal") || node.has("beats")
                || node.has("chapterPurpose") || node.has("openingState")
                || node.has("turningPoint") || node.has("endingState") || node.has("endingHook");
    }

    private List<Beat> beats(List<Beat> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_BEAT_COUNT) {
            throw invalid("beats 数量必须在 1 到 50 之间");
        }
        Set<String> keys = new LinkedHashSet<>();
        List<Beat> result = new ArrayList<>();
        for (Beat beat : values) {
            if (beat == null) {
                throw invalid("beats 不能包含空值");
            }
            String key = required(beat.beatKey(), "beat.beatKey", MAX_BEAT_KEY_LENGTH);
            if (!keys.add(key)) {
                throw invalid("beat.beatKey 必须唯一");
            }
            result.add(new Beat(key, required(beat.summary(), "beat.summary", MAX_BEAT_SUMMARY_LENGTH)));
        }
        return List.copyOf(result);
    }

    private List<String> constraints(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_CONSTRAINT_COUNT) {
            throw invalid("constraints 数量超过限制");
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(required(value, "constraints", MAX_CONSTRAINT_LENGTH));
        }
        return List.copyOf(result);
    }

    private String required(String value, String field, int maxLength) {
        String normalized = optional(value, field, maxLength);
        if (!StringUtils.hasText(normalized)) {
            throw invalid(field + " 不能为空");
        }
        return normalized;
    }

    private String optional(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw invalid(field + " 长度超过限制");
        }
        return normalized;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.OUTLINE_CANDIDATE_INVALID, message);
    }
}
