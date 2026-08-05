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
            String expectedOutcome, String status, List<String> outlineBeatKeys) {
        /** 兼容尚未写入节拍映射的旧场景规划。 */
        public ScenePlanContent(String sceneKey, Integer sequence, String title, PlanReference viewpointCharacter,
                String timeAnchor, PlanReference location, String goal, String conflict, String emotion, String pacing,
                List<PlanReference> participants, List<PlanReference> requiredSettings,
                List<ForeshadowingAction> foreshadowingActions, String expectedOutcome, String status) {
            this(sceneKey, sequence, title, viewpointCharacter, timeAnchor, location, goal, conflict, emotion, pacing,
                    participants, requiredSettings, foreshadowingActions, expectedOutcome, status, List.of());
        }
    }

    /**
     * @deprecated V2 场景规划不再拥有章节方向；仅保留为旧客户端只读投影。
     */
    @Deprecated
    public record ChapterPlanContent(String chapterGoal, String chapterConflict, String expectedOutcome) {
    }

    public record NarrativePlanView(Long id, Long workId, Integer planNo, String status,
            NarrativePlanContent content, Integer version, LocalDateTime gmtCreate, LocalDateTime gmtModified) {
    }

    public record ScenePlanView(Long scenePlanId, String sceneKey, Integer sequence, ScenePlanContent content) {
    }

    public record SourceRef(String sourceType, String sourceId, String contentVersion) {
    }

    public record ChapterPlanView(Long id, Long chapterId, Integer planNo, String status,
            Long narrativePlanId, Integer narrativePlanNo, Long outlineId, Integer outlineRevision,
            Long aiTaskId, Long agentRunId, ChapterPlanContent content, List<ScenePlanView> scenes,
            Integer outlineContentSchemaVersion, String outlineMigrationReviewStatus,
            Long contextSnapshotId, Long sourceSnapshotId, List<SourceRef> sourceRefs,
            String validityStatus, List<String> validityReasonCodes,
            Integer version, LocalDateTime gmtCreate, LocalDateTime gmtModified) {
        /** 兼容 V1 场景规划视图构造。 */
        public ChapterPlanView(Long id, Long chapterId, Integer planNo, String status, Long narrativePlanId,
                Integer narrativePlanNo, Long outlineId, Integer outlineRevision, Long aiTaskId, Long agentRunId,
                ChapterPlanContent content, List<ScenePlanView> scenes, Integer version, LocalDateTime gmtCreate,
                LocalDateTime gmtModified) {
            this(id, chapterId, planNo, status, narrativePlanId, narrativePlanNo, outlineId, outlineRevision, aiTaskId,
                    agentRunId, content, scenes, 1, "review_required", null, null, List.of(), null, List.of(),
                    version, gmtCreate, gmtModified);
        }
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

    public record PublishScenePlanRequest(Integer baseVersion, Long consistencyReportId, Boolean acknowledgeUnknown) {
        /** 兼容尚未启用一致性门禁的旧客户端请求。 */
        public PublishScenePlanRequest(Integer baseVersion) {
            this(baseVersion, null, Boolean.FALSE);
        }
    }
}
