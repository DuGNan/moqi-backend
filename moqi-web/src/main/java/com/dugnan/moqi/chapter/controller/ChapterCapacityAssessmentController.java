package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityAssessmentService;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CapacityAssessmentView;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.CreateAssessmentRequest;
import com.dugnan.moqi.chapter.capacity.ChapterCapacityModels.RetryAssessmentRequest;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 提供章节容量评估的创建、查询、取消与失败重试接口。
 */
@RestController
@RequestMapping("/api")
public class ChapterCapacityAssessmentController {

    private final ChapterCapacityAssessmentService service;

    public ChapterCapacityAssessmentController(ChapterCapacityAssessmentService service) {
        this.service = service;
    }

    @PostMapping("/chapters/{chapterId}/capacity-assessments")
    public ApiResponse<CapacityAssessmentView> create(
            @PathVariable Long chapterId,
            @RequestBody CreateAssessmentRequest request) {
        return ApiResponse.success(service.create(chapterId, request));
    }

    @GetMapping("/chapter-capacity-assessments/{assessmentId}")
    public ApiResponse<CapacityAssessmentView> get(@PathVariable Long assessmentId) {
        return ApiResponse.success(service.get(assessmentId));
    }

    @PostMapping("/chapter-capacity-assessments/{assessmentId}/retry")
    public ApiResponse<AgentRunView> retry(
            @PathVariable Long assessmentId,
            @RequestBody RetryAssessmentRequest request) {
        return ApiResponse.success(service.retry(assessmentId, request));
    }

    @PostMapping("/chapter-capacity-assessments/{assessmentId}/cancel")
    public ApiResponse<AgentRunView> cancel(@PathVariable Long assessmentId) {
        return ApiResponse.success(service.cancel(assessmentId));
    }
}
