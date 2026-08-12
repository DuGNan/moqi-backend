package com.dugnan.moqi.planning.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.planning.SceneOutlineRevisionBridgeService;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.CloneScenePlanCandidateRequest;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.CreateOutlineRevisionCandidateRequest;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.OutlineRevisionCandidateCreated;
import com.dugnan.moqi.planning.SceneOutlineRevisionModels.ScenePlanRevisionDraft;

/**
 * @author dgn
 * @date 2026-08-12
 * @description 提供场景修订草稿和章纲修订候选来源桥接接口。
 */
@RestController
@RequestMapping("/api/chapters")
public class SceneOutlineRevisionBridgeController {
    private final SceneOutlineRevisionBridgeService bridgeService;

    public SceneOutlineRevisionBridgeController(SceneOutlineRevisionBridgeService bridgeService) {
        this.bridgeService = bridgeService;
    }

    @PostMapping("/{chapterId}/scene-plan-candidates/from-current")
    public ApiResponse<ScenePlanRevisionDraft> cloneFromCurrent(
            @PathVariable Long chapterId,
            @RequestBody CloneScenePlanCandidateRequest request) {
        return ApiResponse.success(bridgeService.cloneFromCurrent(chapterId, request));
    }

    @PostMapping("/{chapterId}/scene-plan-candidates/{planId}/outline-revision-candidates")
    public ApiResponse<OutlineRevisionCandidateCreated> createOutlineCandidate(
            @PathVariable Long chapterId,
            @PathVariable Long planId,
            @RequestBody CreateOutlineRevisionCandidateRequest request) {
        return ApiResponse.success(bridgeService.createOutlineCandidate(chapterId, planId, request));
    }
}
