package com.dugnan.moqi.release;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.EvaluationReportView;
import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.RetryEvaluationRequest;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-09-15
 * @description 暴露修订正文质量评价的启动、读取和安全重试接口。
 */
@RestController
@RequestMapping("/api/works/{workId}/story-revisions/chapters/{chapterId}/revisions/{revisionId}/quality-evaluation")
public class RevisionQualityController {
    private final RevisionQualityService service;

    public RevisionQualityController(RevisionQualityService service) {
        this.service = service;
    }

    public record StartRequest(Integer expectedVersion) {
    }

    @PostMapping
    public ApiResponse<EvaluationReportView> start(@PathVariable Long workId, @PathVariable Long chapterId,
            @PathVariable Long revisionId, @RequestBody StartRequest request) {
        return ApiResponse.success(service.start(workId, chapterId, revisionId, request.expectedVersion()));
    }

    @GetMapping
    public ApiResponse<EvaluationReportView> latest(@PathVariable Long workId, @PathVariable Long chapterId,
            @PathVariable Long revisionId) {
        return ApiResponse.success(service.latest(workId, chapterId, revisionId));
    }

    @PostMapping("/{reportId}/retry")
    public ApiResponse<EvaluationReportView> retry(@PathVariable Long workId, @PathVariable Long chapterId,
            @PathVariable Long revisionId, @PathVariable Long reportId,
            @RequestBody RetryEvaluationRequest request) {
        return ApiResponse.success(service.retry(workId, chapterId, revisionId, reportId, request));
    }
}
