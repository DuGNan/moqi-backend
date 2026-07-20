package com.dugnan.moqi.knowledge.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 集中定义设定、伏笔、章节摘要和关键事件接口模型。
 */
public final class KnowledgeModels {

    /**
     * 禁止实例化模型容器。
     */
    private KnowledgeModels() {
    }

    public record SettingCandidateList(Long workId, List<SettingCandidateDetail> candidates) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SettingCandidateDetail(
            Long id,
            Long workId,
            Long chapterId,
            String sourceType,
            Long sourceId,
            Integer sourceContentRevision,
            Integer sourceStartOffset,
            Integer sourceEndOffset,
            String settingType,
            String name,
            String content,
            String candidateStatus,
            Long confirmedSettingId,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record ConfirmSettingRequest(
            String settingType,
            String name,
            String content,
            Long mergeToSettingId) {
    }

    public record ConfirmSettingResult(
            Long candidateId,
            String candidateStatus,
            Long settingId,
            LocalDateTime gmtModified) {
    }

    public record IgnoreSettingRequest(String reason) {
    }

    public record IgnoreSettingResult(
            Long candidateId,
            String candidateStatus,
            LocalDateTime gmtModified) {
    }

    public record SettingList(Long workId, List<SettingDetail> settings) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SettingDetail(
            Long id,
            Long workId,
            String settingType,
            String name,
            JsonNode aliases,
            String content,
            JsonNode attributes,
            Long sourceChapterId,
            Long sourceCandidateId,
            String entryStatus,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record ForeshadowingList(Long workId, List<ForeshadowingDetail> foreshadowings) {
    }

    public record CreateForeshadowingRequest(
            Long sourceChapterId,
            String title,
            String description,
            String sourceText,
            Integer sourceStartOffset,
            Integer sourceEndOffset,
            String status,
            Long expectedPayoffChapterId) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ForeshadowingDetail(
            Long id,
            Long workId,
            Long sourceChapterId,
            String title,
            String description,
            String sourceText,
            Integer sourceStartOffset,
            Integer sourceEndOffset,
            String status,
            Long expectedPayoffChapterId,
            Long actualPayoffChapterId,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChapterSummaryDetail(
            Long id,
            Long workId,
            Long chapterId,
            String summary,
            JsonNode characterChanges,
            JsonNode newSettings,
            JsonNode newForeshadowing,
            JsonNode openQuestions,
            String summaryStatus,
            Integer contentRevision,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record ChapterKeyEventList(Long workId, Long chapterId, List<ChapterKeyEventDetail> events) {
    }

    public record ChapterKeyEventDetail(
            Long id,
            Long workId,
            Long chapterId,
            String eventTitle,
            String eventContent,
            String eventType,
            Integer occurredOrder,
            JsonNode relatedSettingIds,
            JsonNode relatedForeshadowingIds,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }
}
