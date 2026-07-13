/**
 * @author dgn
 * @date:2026-07-13
 * @description:集中定义作品与章节接口使用的业务数据模型。
 */
package com.dugnan.moqi.work.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class WorkChapterModels {

    private WorkChapterModels() {
    }

    public record WorkSummary(
            Long id,
            String title,
            String status,
            long chapterCount,
            Long latestChapterId,
            String latestChapterTitle,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record WorkList(List<WorkSummary> works) {
    }

    public record WorkDetail(
            Long id,
            String title,
            String status,
            long chapterCount,
            long settingCount,
            long foreshadowingCount,
            long pendingSettingCount,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record ChapterSummary(
            Long id,
            Long workId,
            String title,
            Integer chapterNo,
            String chapterType,
            String workflowStatus,
            int wordCount,
            boolean hasPreviewGeneration,
            Integer version,
            LocalDateTime gmtModified) {
    }

    public record ChapterList(WorkRef work, List<ChapterSummary> chapters) {
    }

    public record WorkRef(Long id, String title) {
    }

    public record ChapterCreated(
            Long id,
            Long workId,
            String title,
            Integer chapterNo,
            String chapterType,
            String workflowStatus,
            Integer version,
            String defaultWorkspace,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record ChapterDetail(
            Long id,
            Long workId,
            String workTitle,
            String title,
            Integer chapterNo,
            String chapterType,
            String workflowStatus,
            int wordCount,
            Integer version,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChapterOpen(
            Long workId,
            Long chapterId,
            String defaultWorkspace,
            Long conversationId,
            Long latestPreviewGenerationId,
            Integer contentVersion,
            Long outlineId,
            Integer outlineRevision,
            long pendingSettingCount,
            LocalDateTime gmtModified) {
    }
}
