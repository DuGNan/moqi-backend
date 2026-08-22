package com.dugnan.moqi.chapter.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.dugnan.moqi.common.api.PublicFailure;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 定义整章有界修订任务、停止原因和新候选关联契约。
 */
public final class BoundedChapterRevisionModels {
    private BoundedChapterRevisionModels() {
    }

    public record CreateBoundedRevisionRequest(Long evaluationReportId, String idempotencyKey) {
    }

    public record RetryBoundedRevisionRequest(Integer expectedAttempt) {
    }

    public record BoundedRevisionView(
            Long id,
            Long sourceGenerationId,
            Long sourceReportId,
            Long resultGenerationId,
            Long resultReportId,
            Long aiTaskId,
            Long agentRunId,
            String revisionStatus,
            String stopReason,
            List<String> findingKeys,
            Map<String, Object> revisionBrief,
            String sourceContentHash,
            String resultContentHash,
            Long revisionModelCallId,
            Integer revisionAttempt,
            String errorCode,
            String errorMessage,
            Integer version,
            LocalDateTime gmtCreate,
            LocalDateTime gmtModified,
            PublicFailure failure) {

        /** 兼容公共失败字段发布前的构造调用。 */
        public BoundedRevisionView(
                Long id, Long sourceGenerationId, Long sourceReportId, Long resultGenerationId,
                Long resultReportId, Long aiTaskId, Long agentRunId, String revisionStatus, String stopReason,
                List<String> findingKeys, Map<String, Object> revisionBrief, String sourceContentHash,
                String resultContentHash, Long revisionModelCallId, Integer revisionAttempt, String errorCode,
                String errorMessage, Integer version, LocalDateTime gmtCreate, LocalDateTime gmtModified) {
            this(id, sourceGenerationId, sourceReportId, resultGenerationId, resultReportId, aiTaskId,
                    agentRunId, revisionStatus, stopReason, findingKeys, revisionBrief, sourceContentHash,
                    resultContentHash, revisionModelCallId, revisionAttempt, errorCode, errorMessage, version,
                    gmtCreate, gmtModified, null);
        }
    }
}
