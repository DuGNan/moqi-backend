package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConsensusImpact;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineDetail;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Beat;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Scene;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.ScenePlanDiff;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 集中定义大纲调整候选的 HTTP 与服务层数据模型。
 */
public final class OutlineCandidateModels {

    private OutlineCandidateModels() {
    }

    public record CreateOutlineCandidateRequest(
            Long conversationId,
            Long confirmedBriefId,
            Integer baseOutlineRevision,
            String instruction,
            String candidateType,
            String idempotencyKey) {

        /**
         * 兼容旧调整候选调用方。
         */
        public CreateOutlineCandidateRequest(
                Long conversationId,
                Long confirmedBriefId,
                Integer baseOutlineRevision,
                String instruction) {
            this(conversationId, confirmedBriefId, baseOutlineRevision, instruction, "adjustment", null);
        }
    }

    public record UpdateOutlineCandidateRequest(
            OutlineCandidateContent candidateContent,
            Integer baseCandidateVersion) {
    }

    public record SceneRevisionOutlineCandidateCommand(
            CreateOutlineCandidateRequest request,
            Long sourceScenePlanId,
            Integer sourceScenePlanVersion,
            Long sourceConsistencyReportId,
            String sceneDiffJson) {
    }

    public record RefreshOutlineRequest(
            Long conversationId,
            Long briefId,
            Integer baseRevision,
            String instruction) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OutlineCandidateCreated(
            Long chapterId,
            Long outlineId,
            Integer baseOutlineRevision,
            Long candidateId,
            Long aiTaskId,
            String taskStatus,
            String candidateType,
            String idempotencyKey) {

        /**
         * 兼容旧调整候选响应构造。
         */
        public OutlineCandidateCreated(
                Long chapterId,
                Long outlineId,
                Integer baseOutlineRevision,
                Long candidateId,
                Long aiTaskId,
                String taskStatus) {
            this(chapterId, outlineId, baseOutlineRevision, candidateId, aiTaskId, taskStatus, "adjustment", null);
        }
    }

    public record ValueDiff(boolean changed, String beforeValue, String afterValue) {
    }

    public record CollectionDiff(boolean changed, List<String> beforeValues, List<String> afterValues) {
    }

    public record BeatDiff(
            String beatKey,
            String changeType,
            Integer beforeIndex,
            Integer afterIndex,
            List<String> changedFields,
            Beat beforeBeat,
            Beat afterBeat) {
    }

    /** @deprecated V1 客户端使用的场景差异投影。 */
    @Deprecated
    public record SceneDiff(String sceneId, String changeType, Integer beforeIndex, Integer afterIndex,
            List<String> changedFields, Scene beforeScene, Scene afterScene) {
    }

    public record OutlineCandidateDiff(
            ValueDiff goal,
            ValueDiff coreConflict,
            CollectionDiff constraints,
            List<BeatDiff> beats) {
        /** @deprecated 新客户端请读取 beats。 */
        @Deprecated
        public List<SceneDiff> scenes() {
            return beats.stream().map(diff -> new SceneDiff(diff.beatKey(), diff.changeType(), diff.beforeIndex(),
                    diff.afterIndex(), diff.changedFields().stream()
                            .map(field -> "summary".equals(field) ? "content" : field).toList(),
                    diff.beforeBeat() == null ? null : diff.beforeBeat().toLegacyScene(),
                    diff.afterBeat() == null ? null : diff.afterBeat().toLegacyScene())).toList();
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OutlineCandidateDetail(
            Long id,
            Long workId,
            Long chapterId,
            Long conversationId,
            Long aiTaskId,
            Long confirmedBriefId,
            String candidateType,
            String idempotencyKey,
            Long sourceScenePlanId,
            Integer sourceScenePlanVersion,
            Long sourceConsistencyReportId,
            ScenePlanDiff sceneDiff,
            Integer candidateVersion,
            Long baseOutlineId,
            Integer baseOutlineRevision,
            OutlineCandidateContent baseOutlineContent,
            String candidateStatus,
            String instruction,
            OutlineCandidateContent candidateContent,
            OutlineCandidateDiff diff,
            ConsensusImpact consensusImpact,
            Long resultOutlineId,
            Integer resultOutlineRevision,
            Integer contentSchemaVersion,
            String migrationReviewStatus,
            List<String> migrationReasonCodes,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record OutlineCandidateConfirmation(OutlineCandidateDetail candidate, OutlineDetail outline) {
    }
}
