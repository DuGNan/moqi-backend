package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.AcceptRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.ContinueRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.CreateRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.PlanningChangePackageView;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.RetryRequest;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceModels.View;
import com.dugnan.moqi.chapter.selection.SelectionAssistanceService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 提供章节选区讨论、局部改写候选和人工处置 HTTP 接口。
 */
@RestController
@RequestMapping("/api")
public class SelectionAssistanceController {

    private final SelectionAssistanceService assistanceService;

    public SelectionAssistanceController(SelectionAssistanceService assistanceService) {
        this.assistanceService = assistanceService;
    }

    @PostMapping("/chapters/{chapterId}/selection-assistance")
    public ApiResponse<View> create(@PathVariable Long chapterId, @RequestBody CreateRequest request) {
        return ApiResponse.success(assistanceService.create(chapterId, request));
    }

    @GetMapping("/selection-assistance/{requestId}")
    public ApiResponse<View> get(@PathVariable Long requestId) {
        return ApiResponse.success(assistanceService.get(requestId));
    }

    @PostMapping("/selection-assistance/{requestId}/retry")
    public ApiResponse<AgentRunView> retry(@PathVariable Long requestId, @RequestBody RetryRequest request) {
        return ApiResponse.success(assistanceService.retry(requestId, request));
    }

    @PostMapping("/selection-assistance/{requestId}/cancel")
    public ApiResponse<AgentRunView> cancel(@PathVariable Long requestId) {
        return ApiResponse.success(assistanceService.cancel(requestId));
    }

    @PostMapping("/selection-assistance/{requestId}/reject")
    public ApiResponse<View> reject(@PathVariable Long requestId) {
        return ApiResponse.success(assistanceService.reject(requestId));
    }

    @PostMapping("/selection-assistance/{requestId}/continue")
    public ApiResponse<View> continueCandidate(@PathVariable Long requestId, @RequestBody ContinueRequest request) {
        return ApiResponse.success(assistanceService.continueFrom(requestId, request));
    }

    @PostMapping("/selection-assistance/{requestId}/accept")
    public ApiResponse<View> accept(@PathVariable Long requestId, @RequestBody AcceptRequest request) {
        return ApiResponse.success(assistanceService.accept(requestId, request));
    }

    @GetMapping("/selection-assistance/{requestId}/planning-change-package")
    public ApiResponse<PlanningChangePackageView> planningChangePackage(@PathVariable Long requestId) {
        return ApiResponse.success(assistanceService.getPlanningChangePackage(requestId));
    }
}
