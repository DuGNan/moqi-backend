package com.dugnan.moqi.llm;

import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.entity.LlmModelCallEntity;
import com.dugnan.moqi.chapter.mapper.LlmModelCallMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.llm.dto.LlmCallAggregateRow;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallDetail;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallPage;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallQuery;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallSummary;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallSummaryItem;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmSummaryQuery;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 以当前本地用户权限执行分页明细和白名单维度聚合查询。
 */
@Service
public class LlmObservabilityServiceImpl implements LlmObservabilityService {

    private static final String LOCAL_USER = "local-user";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> GROUPS = Set.of("date", "user", "work", "model", "workflow");
    private static final Set<String> STATUSES =
            Set.of("running", "succeeded", "completed", "failed", "canceled", "unknown");

    private final LlmModelCallMapper callMapper;

    public LlmObservabilityServiceImpl(LlmModelCallMapper callMapper) {
        this.callMapper = callMapper;
    }

    @Override
    public LlmCallPage list(LlmCallQuery query) {
        TimeWindow window = timeWindow(query == null ? null : query.from(), query == null ? null : query.to());
        int page = positive(query == null ? null : query.page(), 1, "page");
        int pageSize = positive(query == null ? null : query.pageSize(), DEFAULT_PAGE_SIZE, "pageSize");
        if (pageSize > MAX_PAGE_SIZE) {
            throw badRequest("pageSize 不能超过 " + MAX_PAGE_SIZE);
        }
        String status = trim(query == null ? null : query.callStatus());
        if (status != null && !STATUSES.contains(status)) {
            throw badRequest("callStatus 不在允许范围内");
        }
        long offset = (long) (page - 1) * pageSize;
        String provider = trim(query == null ? null : query.provider());
        String model = trim(query == null ? null : query.model());
        String workflow = trim(query == null ? null : query.workflowType());
        Long workId = query == null ? null : query.workId();
        Long chapterId = query == null ? null : query.chapterId();
        long total = callMapper.countRecent(
                LOCAL_USER, window.from(), window.to(), workId, chapterId, provider, model, workflow, status);
        return new LlmCallPage(
                total,
                page,
                pageSize,
                callMapper.selectRecent(
                                LOCAL_USER,
                                window.from(),
                                window.to(),
                                workId,
                                chapterId,
                                provider,
                                model,
                                workflow,
                                status,
                                offset,
                                pageSize)
                        .stream()
                        .map(this::detail)
                        .toList());
    }

    @Override
    public LlmCallSummary summarize(LlmSummaryQuery query) {
        TimeWindow window = timeWindow(query == null ? null : query.from(), query == null ? null : query.to());
        String groupBy = trim(query == null ? null : query.groupBy());
        groupBy = groupBy == null ? "date" : groupBy;
        if (!GROUPS.contains(groupBy)) {
            throw badRequest("groupBy 只允许 date、user、work、model 或 workflow");
        }
        var rows = callMapper.summarize(
                LOCAL_USER,
                window.from(),
                window.to(),
                query == null ? null : query.workId(),
                trim(query == null ? null : query.provider()),
                trim(query == null ? null : query.model()),
                trim(query == null ? null : query.workflowType()),
                groupBy);
        return new LlmCallSummary(
                window.from(),
                window.to(),
                groupBy,
                true,
                rows.stream().map(this::summary).toList());
    }

    private LlmCallDetail detail(LlmModelCallEntity call) {
        return new LlmCallDetail(
                call.getId(),
                call.getWorkId(),
                call.getChapterId(),
                call.getAiTaskId(),
                call.getAgentRunId(),
                call.getAgentStepId(),
                call.getWorkflowType(),
                call.getOperationType(),
                call.getLogicalCallId(),
                call.getAttemptNo(),
                call.getProvider(),
                call.getModel(),
                call.getCallStatus(),
                call.getFinishReason(),
                call.getInputTokens(),
                call.getOutputTokens(),
                call.getTotalTokens(),
                call.getErrorCode(),
                call.getElapsedMillis(),
                call.getEstimatedCost(),
                call.getCurrency(),
                call.getCostStatus(),
                call.getStartedAt(),
                call.getFinishedAt());
    }

    private LlmCallSummaryItem summary(LlmCallAggregateRow row) {
        return new LlmCallSummaryItem(
                row.getGroupKey(),
                row.getAttemptCount(),
                row.getLogicalCallCount(),
                row.getSuccessCount(),
                row.getFailureCount(),
                row.getCanceledCount(),
                row.getTimeoutCount(),
                row.getRateLimitedCount(),
                row.getInputTokens(),
                row.getOutputTokens(),
                row.getTotalTokens(),
                row.getEstimatedCost(),
                row.getUnpricedCount(),
                row.getAverageElapsedMillis());
    }

    private TimeWindow timeWindow(LocalDateTime from, LocalDateTime to) {
        LocalDateTime safeTo = to == null ? LocalDateTime.now() : to;
        LocalDateTime safeFrom = from == null ? safeTo.minusDays(30) : from;
        if (!safeFrom.isBefore(safeTo)) {
            throw badRequest("from 必须早于 to");
        }
        return new TimeWindow(safeFrom, safeTo);
    }

    private int positive(Integer value, int defaultValue, String field) {
        int resolved = value == null ? defaultValue : value;
        if (resolved <= 0) {
            throw badRequest(field + " 必须大于 0");
        }
        return resolved;
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private record TimeWindow(LocalDateTime from, LocalDateTime to) {
    }
}
