package com.dugnan.moqi.chapter.outline;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 定义只描述章节方向与节拍的 V2 章纲内容契约。
 */
public record OutlineCandidateContent(
        Integer schemaVersion,
        String chapterPurpose,
        String openingState,
        @JsonAlias("goal") String chapterGoal,
        String coreConflict,
        List<Beat> beats,
        String turningPoint,
        String endingState,
        String endingHook,
        List<String> constraints) {

    public static final int SCHEMA_VERSION = 2;

    /**
     * 兼容 V1 调用方构造的章纲。该构造仅用于读取、编辑旧数据；新写入始终使用 V2 字段。
     */
    public OutlineCandidateContent(String goal, String coreConflict, List<Scene> scenes, List<String> constraints) {
        this(SCHEMA_VERSION, null, null, goal, coreConflict,
                scenes == null ? List.of() : scenes.stream().map(Scene::toBeat).toList(),
                null, null, null, constraints);
    }

    /** @deprecated 新代码请使用 chapterGoal。 */
    @Deprecated
    public String goal() {
        return chapterGoal;
    }

    /** @deprecated 新代码请使用 beats。 */
    @Deprecated
    public List<Scene> scenes() {
        return beats == null ? List.of() : beats.stream().map(Beat::toLegacyScene).toList();
    }

    /**
     * 定义章纲中的稳定叙事节拍。
     */
    public record Beat(String beatKey, String summary) {
        public Scene toLegacyScene() {
            return new Scene(beatKey, beatKey, summary, List.of());
        }
    }

    /**
     * 仅用于兼容 V1 API 与 JSON 投影的旧场景表示；不得作为 V2 写入模型。
     */
    @Deprecated
    public record Scene(String id, String title, String content, List<String> tags) {
        public Beat toBeat() {
            String summary = title == null || title.isBlank() ? content : title + "：" + content;
            return new Beat(id, summary);
        }
    }
}
