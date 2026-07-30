package com.dugnan.moqi.chapter.outline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Scene;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 读取、规范化并校验大纲调整候选的结构化 JSON 内容。
 */
@Component
public class OutlineCandidateContentCodec {

    private static final int MAX_GOAL_LENGTH = 2000;
    private static final int MAX_SCENE_COUNT = 50;
    private static final int MAX_SCENE_ID_LENGTH = 128;
    private static final int MAX_SCENE_TITLE_LENGTH = 200;
    private static final int MAX_SCENE_CONTENT_LENGTH = 4000;
    private static final int MAX_TAG_COUNT = 20;
    private static final int MAX_TAG_LENGTH = 64;
    private static final int MAX_CONSTRAINT_COUNT = 50;
    private static final int MAX_CONSTRAINT_LENGTH = 500;

    private final ObjectMapper objectMapper;

    /**
     * 创建内容编解码器。
     *
     * @param objectMapper JSON 映射器
     */
    public OutlineCandidateContentCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析并校验已保存 JSON。
     *
     * @param json JSON 内容
     * @return 规范化内容
     */
    public OutlineCandidateContent read(String json) {
        if (!StringUtils.hasText(json)) {
            throw invalid("候选大纲内容不能为空");
        }
        try {
            return normalize(objectMapper.readValue(json, OutlineCandidateContent.class));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.OUTLINE_CANDIDATE_INVALID, "候选大纲 JSON 无法读取", exception);
        }
    }

    /**
     * 序列化规范化内容。
     *
     * @param content 候选内容
     * @return JSON 内容
     */
    public String write(OutlineCandidateContent content) {
        try {
            return objectMapper.writeValueAsString(normalize(content));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "候选大纲无法序列化", exception);
        }
    }

    /**
     * 规范化模型或接口返回的候选内容。
     *
     * @param content 原始内容
     * @return 规范化内容
     */
    public OutlineCandidateContent normalize(OutlineCandidateContent content) {
        if (content == null) {
            throw invalid("候选大纲不能为空");
        }
        String goal = required(content.goal(), "goal", MAX_GOAL_LENGTH);
        String coreConflict = required(content.coreConflict(), "coreConflict", MAX_GOAL_LENGTH);
        List<Scene> scenes = normalizeScenes(content.scenes());
        List<String> constraints = normalizeConstraints(content.constraints());
        return new OutlineCandidateContent(goal, coreConflict, scenes, constraints);
    }

    private List<Scene> normalizeScenes(List<Scene> scenes) {
        if (scenes == null || scenes.isEmpty()) {
            throw invalid("scenes 至少包含一个场景");
        }
        if (scenes.size() > MAX_SCENE_COUNT) {
            throw invalid("scenes 数量超过限制");
        }
        Set<String> ids = new LinkedHashSet<>();
        List<Scene> normalized = new ArrayList<>();
        for (Scene scene : scenes) {
            if (scene == null) {
                throw invalid("scenes 不能包含空值");
            }
            String id = required(scene.id(), "scene.id", MAX_SCENE_ID_LENGTH);
            if (!ids.add(id)) {
                throw invalid("scene.id 必须唯一");
            }
            String title = required(scene.title(), "scene.title", MAX_SCENE_TITLE_LENGTH);
            String sceneContent = required(scene.content(), "scene.content", MAX_SCENE_CONTENT_LENGTH);
            normalized.add(new Scene(id, title, sceneContent, normalizeTags(scene.tags())));
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        if (tags.size() > MAX_TAG_COUNT) {
            throw invalid("scene.tags 数量超过限制");
        }
        List<String> normalized = new ArrayList<>();
        for (String tag : tags) {
            normalized.add(required(tag, "scene.tags", MAX_TAG_LENGTH));
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeConstraints(List<String> constraints) {
        if (constraints == null) {
            return List.of();
        }
        if (constraints.size() > MAX_CONSTRAINT_COUNT) {
            throw invalid("constraints 数量超过限制");
        }
        List<String> normalized = new ArrayList<>();
        for (String constraint : constraints) {
            normalized.add(required(constraint, "constraints", MAX_CONSTRAINT_LENGTH));
        }
        return List.copyOf(normalized);
    }

    private String required(String value, String fieldName, int maxLength) {
        String normalized = optional(value, fieldName, maxLength);
        if (!StringUtils.hasText(normalized)) {
            throw invalid(fieldName + " 不能为空");
        }
        return normalized;
    }

    private String optional(String value, String fieldName, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw invalid(fieldName + " 长度超过限制");
        }
        return normalized;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.OUTLINE_CANDIDATE_INVALID, message);
    }
}
