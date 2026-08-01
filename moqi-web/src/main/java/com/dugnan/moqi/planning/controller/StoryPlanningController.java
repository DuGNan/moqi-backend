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
import com.dugnan.moqi.planning.PlanningModels.CreateNarrativePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.CreateScenePlanCandidateRequest;
import com.dugnan.moqi.planning.PlanningModels.NarrativePlanView;
import com.dugnan.moqi.planning.PlanningModels.PublishNarrativePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.PublishScenePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.UpdateNarrativePlanRequest;
import com.dugnan.moqi.planning.PlanningModels.UpdateScenePlanCandidateRequest;
import com.dugnan.moqi.planning.StoryPlanningService;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 提供作品叙事规划和章节场景规划的 HTTP 接口。
 */
@RestController
@RequestMapping("/api")
public class StoryPlanningController {
    private final StoryPlanningService planningService;

    public StoryPlanningController(StoryPlanningService planningService) {
        this.planningService = planningService;
    }

    @PostMapping("/works/{workId}/narrative-plans")
    public ApiResponse<NarrativePlanView> createNarrative(@PathVariable Long workId,
            @RequestBody CreateNarrativePlanRequest request) {
        return ApiResponse.success(planningService.createNarrativePlan(workId, request));
    }

    @GetMapping("/works/{workId}/narrative-plans/current")
    public ApiResponse<NarrativePlanView> currentNarrative(@PathVariable Long workId) {
        return ApiResponse.success(planningService.getCurrentNarrativePlan(workId));
    }

    @GetMapping("/works/{workId}/narrative-plans/latest-draft")
    public ApiResponse<NarrativePlanView> latestNarrativeDraft(@PathVariable Long workId) {
        return ApiResponse.success(planningService.getLatestNarrativeDraft(workId));
    }

    @GetMapping("/works/{workId}/narrative-plans/{planId}")
    public ApiResponse<NarrativePlanView> narrative(@PathVariable Long workId, @PathVariable Long planId) {
        return ApiResponse.success(planningService.getNarrativePlan(workId, planId));
    }

    @PutMapping("/works/{workId}/narrative-plans/{planId}")
    public ApiResponse<NarrativePlanView> updateNarrative(@PathVariable Long workId, @PathVariable Long planId,
            @RequestBody UpdateNarrativePlanRequest request) {
        return ApiResponse.success(planningService.updateNarrativePlan(workId, planId, request));
    }

    @PostMapping("/works/{workId}/narrative-plans/{planId}/publish")
    public ApiResponse<NarrativePlanView> publishNarrative(@PathVariable Long workId, @PathVariable Long planId,
            @RequestBody PublishNarrativePlanRequest request) {
        return ApiResponse.success(planningService.publishNarrativePlan(workId, planId, request));
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
}
