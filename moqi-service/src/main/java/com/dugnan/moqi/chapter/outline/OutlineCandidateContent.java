package com.dugnan.moqi.chapter.outline;

import java.util.List;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 定义大纲调整候选和正式大纲共用的结构化内容。
 */
public record OutlineCandidateContent(
        String goal,
        String coreConflict,
        List<Scene> scenes,
        List<String> constraints) {

    /**
     * 定义单个大纲场景。
     *
     * @param id 稳定场景标识
     * @param title 场景标题
     * @param content 场景内容
     * @param tags 场景标签
     */
    public record Scene(String id, String title, String content, List<String> tags) {
    }
}
