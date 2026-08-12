package com.dugnan.moqi.planning.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PlanningModels.CreateScenePlanCandidateRequest;
import com.dugnan.moqi.planning.PlanningModels.PublishScenePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.UpdateScenePlanCandidateRequest;
import com.dugnan.moqi.planning.StoryPlanningService;
import com.dugnan.moqi.planning.ScenePlanConsistencyService;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.CheckRequest;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.ConsistencyReportView;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.DiscussionProposalRequest;
import com.dugnan.moqi.planning.ScenePlanConsistencyModels.RetryRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.BriefView;
import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 提供章节场景规划候选、发布和一致性检查的 HTTP 接口。
 */
@RestController
@RequestMapping("/api")
public class StoryPlanningController {
    private final StoryPlanningService planningService;
    private final ScenePlanConsistencyService consistencyService;

    public StoryPlanningController(StoryPlanningService planningService, ScenePlanConsistencyService consistencyService) {
        this.planningService = planningService;
        this.consistencyService = consistencyService;
    }

    @PostMapping("/chapters/{chapterId}/scene-plan-candidates")
    public ApiResponse<ChapterPlanView> createCandidate(@PathVariable Long chapterId,
            @RequestBody CreateScenePlanCandidateRequest request) {
        return ApiResponse.success(planningService.createCandidate(chapterId, request));
    }

    @GetMapping("/chapters/{chapterId}/scene-plan-candidates/latest")
    public ApiResponse<ChapterPlanView> latestCandidate(@PathVariable Long chapterId) {
        return ApiResponse.success(planningService.getLatestCandidate(chapterId));
    }

    @GetMapping("/chapters/{chapterId}/scene-plan-candidates/{planId}")
    public ApiResponse<ChapterPlanView> candidate(@PathVariable Long chapterId, @PathVariable Long planId) {
        return ApiResponse.success(planningService.getCandidate(chapterId, planId));
    }

    @PutMapping("/chapters/{chapterId}/scene-plan-candidates/{planId}")
    public ApiResponse<ChapterPlanView> updateCandidate(@PathVariable Long chapterId, @PathVariable Long planId,
            @RequestBody UpdateScenePlanCandidateRequest request) {
        return ApiResponse.success(planningService.updateCandidate(chapterId, planId, request));
    }

    @PostMapping("/chapters/{chapterId}/scene-plan-candidates/{planId}/abandon")
    public ApiResponse<ChapterPlanView> abandonCandidate(@PathVariable Long chapterId, @PathVariable Long planId) {
        return ApiResponse.success(planningService.abandonCandidate(chapterId, planId));
    }

    @PostMapping("/chapters/{chapterId}/scene-plan-candidates/{planId}/publish")
    public ApiResponse<ChapterPlanView> publishCandidate(@PathVariable Long chapterId, @PathVariable Long planId,
            @RequestBody PublishScenePlanRequest request) {
        return ApiResponse.success(planningService.publishCandidate(chapterId, planId, request));
    }

    @GetMapping("/chapters/{chapterId}/scene-plans/current")
    public ApiResponse<ChapterPlanView> currentScenePlan(@PathVariable Long chapterId) {
        return ApiResponse.success(planningService.loadCurrent(chapterId));
    }

    @GetMapping("/chapters/{chapterId}/scene-plans/{planNo}")
    public ApiResponse<ChapterPlanView> scenePlan(@PathVariable Long chapterId, @PathVariable Integer planNo) {
        return ApiResponse.success(planningService.loadPublished(chapterId, planNo));
    }

    @PostMapping("/chapters/{chapterId}/scene-plans/{planId}/consistency-reports")
    public ApiResponse<ConsistencyReportView> createConsistencyReport(@PathVariable Long chapterId, @PathVariable Long planId,
            @RequestBody CheckRequest request) {
        return ApiResponse.success(consistencyService.create(chapterId, planId, request));
    }

    @GetMapping("/chapters/{chapterId}/scene-plans/{planId}/consistency-reports/latest")
    public ApiResponse<ConsistencyReportView> latestConsistencyReport(@PathVariable Long chapterId, @PathVariable Long planId) {
        return ApiResponse.success(consistencyService.latest(chapterId, planId));
    }

    @GetMapping("/chapters/{chapterId}/scene-plan-consistency-reports/{reportId}")
    public ApiResponse<ConsistencyReportView> consistencyReport(@PathVariable Long chapterId, @PathVariable Long reportId) {
        return ApiResponse.success(consistencyService.get(chapterId, reportId));
    }

    @PostMapping("/chapters/{chapterId}/scene-plan-consistency-reports/{reportId}/retry")
    public ApiResponse<AgentRunView> retryConsistencyReport(@PathVariable Long chapterId, @PathVariable Long reportId,
            @RequestBody RetryRequest request) {
        return ApiResponse.success(consistencyService.retry(chapterId, reportId, request));
    }

    @PostMapping("/chapters/{chapterId}/scene-plan-consistency-reports/{reportId}/cancel")
    public ApiResponse<AgentRunView> cancelConsistencyReport(@PathVariable Long chapterId, @PathVariable Long reportId) {
        return ApiResponse.success(consistencyService.cancel(chapterId, reportId));
    }

    @PostMapping("/chapters/{chapterId}/scene-plan-consistency-reports/{reportId}/discussion-proposals")
    public ApiResponse<BriefView> createDiscussionProposal(@PathVariable Long chapterId, @PathVariable Long reportId,
            @RequestBody DiscussionProposalRequest request) {
        return ApiResponse.success(consistencyService.createDiscussionProposal(chapterId, reportId, request));
    }
}
