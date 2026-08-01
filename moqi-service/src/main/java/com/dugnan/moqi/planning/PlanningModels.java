package com.dugnan.moqi.planning;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 集中定义三级故事规划的公开数据契约。
 */
public final class PlanningModels {
    private PlanningModels() {
    }

    public record NarrativePlanContent(
            String goal, String premise, String coreConflict, String thematicIntent,
            String endingDirection, List<String> constraints) {
    }

    public record PlanReference(Long settingEntryId, String name) {
    }

    public record ForeshadowingAction(String action, Long foreshadowingItemId, String description) {
    }

    public record ScenePlanContent(
            String sceneKey, Integer sequence, String title, PlanReference viewpointCharacter,
            String timeAnchor, PlanReference location, String goal, String conflict,
            String emotion, String pacing, List<PlanReference> participants,
            List<PlanReference> requiredSettings, List<ForeshadowingAction> foreshadowingActions,
            String expectedOutcome, String status) {
    }

    public record ChapterPlanContent(String chapterGoal, String chapterConflict, String expectedOutcome) {
    }

    public record NarrativePlanView(Long id, Long workId, Integer planNo, String status,
            NarrativePlanContent content, Integer version, LocalDateTime gmtCreate, LocalDateTime gmtModified) {
    }

    public record ScenePlanView(String sceneKey, Integer sequence, ScenePlanContent content) {
    }

    public record ChapterPlanView(Long id, Long chapterId, Integer planNo, String status,
            Long narrativePlanId, Integer narrativePlanNo, Long outlineId, Integer outlineRevision,
            Long aiTaskId, Long agentRunId, ChapterPlanContent content, List<ScenePlanView> scenes,
            Integer version, LocalDateTime gmtCreate, LocalDateTime gmtModified) {
    }

    public record CreateNarrativePlanRequest(NarrativePlanContent content) {
    }

    public record UpdateNarrativePlanRequest(Integer baseVersion, NarrativePlanContent content) {
    }

    public record PublishNarrativePlanRequest(Integer baseVersion) {
    }

    public record CreateScenePlanCandidateRequest(Integer baseOutlineRevision, String idempotencyKey) {
    }

    public record UpdateScenePlanCandidateRequest(
            Integer baseVersion, ChapterPlanContent content, List<ScenePlanContent> scenes) {
    }

    public record PublishScenePlanRequest(Integer baseVersion) {
    }
}
