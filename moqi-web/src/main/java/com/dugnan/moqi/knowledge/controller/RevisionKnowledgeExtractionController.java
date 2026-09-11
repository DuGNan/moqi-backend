package com.dugnan.moqi.knowledge.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.agent.dto.AgentRuntimeModels.AgentRunView;
import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.BatchView;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.RetryExtractionRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.StartExtractionRequest;
import com.dugnan.moqi.knowledge.service.KnowledgeExtractionService;

/**
 * @author dgn
 * @date 2026-09-11
 * @description 提供待发布正文 revision 的知识提取、恢复、重试和取消接口。
 */
@RestController
@RequestMapping("/api/works/{workId}/story-revisions/chapters/{chapterId}/revisions/{revisionId}")
public class RevisionKnowledgeExtractionController {

    private final KnowledgeExtractionService extractionService;

    public RevisionKnowledgeExtractionController(KnowledgeExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @PostMapping("/knowledge-extractions")
    public ApiResponse<BatchView> start(
            @PathVariable Long workId,
            @PathVariable Long chapterId,
            @PathVariable Long revisionId,
            @RequestBody StartExtractionRequest request) {
        return ApiResponse.success(
                extractionService.startRevision(workId, chapterId, revisionId, request));
    }

    @GetMapping("/knowledge-extractions/latest")
    public ApiResponse<BatchView> latest(
            @PathVariable Long workId,
            @PathVariable Long chapterId,
            @PathVariable Long revisionId) {
        return ApiResponse.success(extractionService.latestRevision(workId, chapterId, revisionId));
    }

    @GetMapping("/knowledge-extractions/{batchId}")
    public ApiResponse<BatchView> get(
            @PathVariable Long workId,
            @PathVariable Long chapterId,
            @PathVariable Long revisionId,
            @PathVariable Long batchId) {
        return ApiResponse.success(
                extractionService.getRevision(workId, chapterId, revisionId, batchId));
    }

    @PostMapping("/knowledge-extractions/{batchId}/retry")
    public ApiResponse<AgentRunView> retry(
            @PathVariable Long workId,
            @PathVariable Long chapterId,
            @PathVariable Long revisionId,
            @PathVariable Long batchId,
            @RequestBody RetryExtractionRequest request) {
        return ApiResponse.success(extractionService.retryRevision(
                workId, chapterId, revisionId, batchId, request));
    }

    @PostMapping("/knowledge-extractions/{batchId}/cancel")
    public ApiResponse<AgentRunView> cancel(
            @PathVariable Long workId,
            @PathVariable Long chapterId,
            @PathVariable Long revisionId,
            @PathVariable Long batchId) {
        return ApiResponse.success(
                extractionService.cancelRevision(workId, chapterId, revisionId, batchId));
    }
}
