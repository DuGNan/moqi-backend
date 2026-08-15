package com.dugnan.moqi.impact;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;

/**
 * @author dgn
 * @description 定义正文影响报告、事实变化、受影响资产与工作区摘要契约。
 */
public final class ProseImpactModels {
    private ProseImpactModels() { }

    public record CreateReportRequest(Long workspaceId, Long baselineRevisionId, String idempotencyKey) { }
    public record RetryReportRequest(Integer expectedAttempt) { }
    public record FactChange(String changeKey, String factType, String epistemicStatus, String changeKind,
            String impactScope, String evidenceText, Integer evidenceStartOffset, Integer evidenceEndOffset,
            BigDecimal confidence, Boolean directDependency, String explanation, List<Long> affectedChapterIds) {
        public FactChange(String changeKey, String factType, String epistemicStatus, String changeKind,
                String impactScope, String evidenceText, Integer evidenceStartOffset, Integer evidenceEndOffset,
                BigDecimal confidence, Boolean directDependency, String explanation) {
            this(changeKey, factType, epistemicStatus, changeKind, impactScope, evidenceText,
                    evidenceStartOffset, evidenceEndOffset, confidence, directDependency, explanation, List.of());
        }
    }
    public record ImpactAnalysis(String impactScope, String summary, List<FactChange> changes) { }
    public record ImpactedAssetView(Long chapterId, String assetType, Long assetId, String dependencyType,
            String validityStatus, String reasonCode) { }
    public record ReportView(Long id, Long workId, Long chapterId, Long workspaceId, Long baselineRevisionId,
            Long targetRevisionId, Long baselineReleaseId, Long agentRunId, Long modelCallId,
            String inputFingerprint, String sourceGraphFingerprint, String analyzerVersion,
            String reportStatus, String impactScope,
            boolean blocking, String summary, String errorCode, List<FactChange> changes,
            List<ImpactedAssetView> impactedAssets, Integer version, LocalDateTime gmtCreate,
            LocalDateTime gmtModified) { }
    public record CreateReportResult(ReportView report, AgentRunView run) { }
    public record ImpactBlockingItem(String code, Long revisionId, Long reportId, Long batchId,
            Long candidateId, String status) { }
    public record WorkspaceImpactSummary(int total, int ready, int blocking, int failed, int stale,
            List<Long> reportIds, List<String> scopes, List<ImpactBlockingItem> blockingItems) { }
}
