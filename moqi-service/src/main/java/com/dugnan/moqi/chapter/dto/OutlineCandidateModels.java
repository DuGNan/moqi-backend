package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConsensusImpact;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineDetail;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Scene;

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
            String instruction) {
    }

    public record RefreshOutlineRequest(
            Long conversationId,
            Long briefId,
            Integer baseRevision,
            String instruction) {
    }

    public record OutlineCandidateCreated(
            Long chapterId,
            Long outlineId,
            Integer baseOutlineRevision,
            Long candidateId,
            Long aiTaskId,
            String taskStatus) {
    }

    public record ValueDiff(boolean changed, String beforeValue, String afterValue) {
    }

    public record CollectionDiff(boolean changed, List<String> beforeValues, List<String> afterValues) {
    }

    public record SceneDiff(
            String sceneId,
            String changeType,
            Integer beforeIndex,
            Integer afterIndex,
            List<String> changedFields,
            Scene beforeScene,
            Scene afterScene) {
    }

    public record OutlineCandidateDiff(
            ValueDiff goal,
            ValueDiff coreConflict,
            CollectionDiff constraints,
            List<SceneDiff> scenes) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OutlineCandidateDetail(
            Long id,
            Long workId,
            Long chapterId,
            Long conversationId,
            Long aiTaskId,
            Long confirmedBriefId,
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
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified) {
    }

    public record OutlineCandidateConfirmation(OutlineCandidateDetail candidate, OutlineDetail outline) {
    }
}
