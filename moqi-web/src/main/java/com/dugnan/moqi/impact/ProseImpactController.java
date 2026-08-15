package com.dugnan.moqi.impact;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.impact.ProseImpactModels.CreateReportRequest;
import com.dugnan.moqi.impact.ProseImpactModels.CreateReportResult;
import com.dugnan.moqi.impact.ProseImpactModels.ReportView;
import com.dugnan.moqi.impact.ProseImpactModels.RetryReportRequest;

/**
 * @author dgn
 * @description 暴露正文 revision 影响报告的创建、读取与重试接口。
 */
@RestController
@RequestMapping("/api/works/{workId}/story-revisions/chapters/{chapterId}/revisions/{revisionId}/impact-reports")
public class ProseImpactController {
    private final ProseImpactService service;
    public ProseImpactController(ProseImpactService service) { this.service = service; }

    @PostMapping
    public ApiResponse<CreateReportResult> create(@PathVariable Long workId, @PathVariable Long chapterId,
            @PathVariable Long revisionId, @RequestBody CreateReportRequest request) {
        return ApiResponse.success(service.create(workId, chapterId, revisionId, request));
    }
    @GetMapping("/{reportId}")
    public ApiResponse<ReportView> detail(@PathVariable Long workId, @PathVariable Long chapterId,
            @PathVariable Long revisionId, @PathVariable Long reportId) {
        return ApiResponse.success(service.detail(workId, chapterId, revisionId, reportId));
    }
    @GetMapping("/latest")
    public ApiResponse<ReportView> latest(@PathVariable Long workId, @PathVariable Long chapterId,
            @PathVariable Long revisionId) {
        return ApiResponse.success(service.latest(workId, chapterId, revisionId));
    }
    @PostMapping("/{reportId}/retry")
    public ApiResponse<com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView> retry(
            @PathVariable Long workId, @PathVariable Long chapterId, @PathVariable Long revisionId,
            @PathVariable Long reportId, @RequestBody RetryReportRequest request) {
        return ApiResponse.success(service.retry(workId, chapterId, revisionId, reportId, request));
    }
}
