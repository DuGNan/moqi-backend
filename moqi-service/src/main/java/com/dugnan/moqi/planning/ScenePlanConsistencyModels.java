package com.dugnan.moqi.planning;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 集中定义场景规划一致性检查的公开请求和响应契约。
 */
public final class ScenePlanConsistencyModels {
    private ScenePlanConsistencyModels() {
    }

    public record CheckRequest(Integer baseVersion, String idempotencyKey) {
    }

    public record RetryRequest(Integer expectedAttempt) {
    }

    public record DiscussionProposalRequest(Long baseBriefId, Integer baseBriefVersion, List<String> issueKeys,
            String idempotencyKey) {
    }

    public record FindingSourceRef(String sourceType, String sourceId, String contentVersion, String fieldKey,
            String summary) {
    }

    public record ConsistencyFinding(String issueKey, String resultStatus, String severity, Double confidence,
            String origin, List<String> sceneKeys, List<String> fields, List<FindingSourceRef> sourceRefs,
            String differenceSummary, String suggestedAction) {
    }

    public record ConsistencyReportView(Long id, Long chapterId, Long scenePlanId, Integer planVersion,
            Long sourceSnapshotId, Long aiTaskId, Long agentRunId, String reportStatus, String resultStatus,
            List<ConsistencyFinding> findings, String resolutionStatus, String rulesetVersion, String evaluatorVersion,
            Integer version, LocalDateTime gmtCreate, LocalDateTime gmtModified) {
    }
}
