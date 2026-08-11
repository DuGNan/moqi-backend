package com.dugnan.moqi.planning;

import java.util.List;

import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateCreated;

/**
 * @author dgn
 * @date 2026-08-12
 * @description 定义场景修订草稿与章纲候选桥接接口的数据契约。
 */
public final class SceneOutlineRevisionModels {

    private SceneOutlineRevisionModels() {
    }

    public record CloneScenePlanCandidateRequest(
            Long sourcePlanId,
            Integer baseOutlineRevision,
            String idempotencyKey) {
    }

    public record ScenePlanRevisionDraft(
            Long planId,
            Long sourcePlanId,
            Integer sourcePlanVersion,
            Integer outlineRevision,
            String status,
            Integer version,
            String idempotencyKey) {
    }

    public record CreateOutlineRevisionCandidateRequest(
            Integer baseVersion,
            Long consistencyReportId,
            Long conversationId,
            Long confirmedBriefId,
            Integer baseOutlineRevision,
            String idempotencyKey) {
    }

    public record ScenePlanChange(
            String sceneKey,
            String changeType,
            Integer beforeSequence,
            Integer afterSequence,
            List<String> changedFields) {
    }

    public record ScenePlanDiff(
            Long sourcePlanId,
            Integer sourcePlanVersion,
            Long candidatePlanId,
            Integer candidatePlanVersion,
            List<ScenePlanChange> changes) {
    }

    public record OutlineRevisionCandidateCreated(
            OutlineCandidateCreated candidate,
            ScenePlanDiff sceneDiff) {
    }
}
