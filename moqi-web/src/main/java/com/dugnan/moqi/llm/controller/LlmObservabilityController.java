package com.dugnan.moqi.llm.controller;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.llm.LlmObservabilityService;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallPage;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallQuery;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmCallSummary;
import com.dugnan.moqi.llm.dto.LlmObservabilityModels.LlmSummaryQuery;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 提供当前用户模型调用明细和估算成本聚合查询接口。
 */
@RestController
@RequestMapping("/api/llm-calls")
public class LlmObservabilityController {

    private final LlmObservabilityService observabilityService;

    public LlmObservabilityController(LlmObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @GetMapping
    public ApiResponse<LlmCallPage> calls(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Long workId,
            @RequestParam(required = false) Long chapterId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String workflowType,
            @RequestParam(required = false) String callStatus,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(observabilityService.list(new LlmCallQuery(
                from, to, workId, chapterId, provider, model, workflowType, callStatus, page, pageSize)));
    }

    @GetMapping("/summary")
    public ApiResponse<LlmCallSummary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Long workId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String workflowType,
            @RequestParam(required = false) String groupBy) {
        return ApiResponse.success(observabilityService.summarize(new LlmSummaryQuery(
                from, to, workId, provider, model, workflowType, groupBy)));
    }
}
