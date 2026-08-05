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
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.CandidateDecision;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.ConfirmCandidateRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.IgnoreCandidateRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.RetryExtractionRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeExtractionModels.StartExtractionRequest;
import com.dugnan.moqi.knowledge.service.KnowledgeExtractionService;

/**
 * @author dgn
 * @date 2026-08-05
 * @description 提供已采纳正文知识提取批次与候选人工决策接口。
 */
@RestController
@RequestMapping("/api")
public class KnowledgeExtractionController {

    private final KnowledgeExtractionService extractionService;

    public KnowledgeExtractionController(KnowledgeExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @PostMapping("/chapters/{chapterId}/generations/{generationId}/knowledge-extractions")
    public ApiResponse<BatchView> start(
            @PathVariable Long chapterId,
            @PathVariable Long generationId,
            @RequestBody StartExtractionRequest request) {
        return ApiResponse.success(extractionService.start(chapterId, generationId, request));
    }

    @GetMapping("/chapters/{chapterId}/generations/{generationId}/knowledge-extractions/latest")
    public ApiResponse<BatchView> latest(
            @PathVariable Long chapterId,
            @PathVariable Long generationId) {
        return ApiResponse.success(extractionService.latest(chapterId, generationId));
    }

    @GetMapping("/chapters/{chapterId}/generations/{generationId}/knowledge-extractions/{batchId}")
    public ApiResponse<BatchView> get(
            @PathVariable Long chapterId,
            @PathVariable Long generationId,
            @PathVariable Long batchId) {
        return ApiResponse.success(extractionService.get(chapterId, generationId, batchId));
    }

    @PostMapping("/chapters/{chapterId}/generations/{generationId}/knowledge-extractions/{batchId}/retry")
    public ApiResponse<AgentRunView> retry(
            @PathVariable Long chapterId,
            @PathVariable Long generationId,
            @PathVariable Long batchId,
            @RequestBody RetryExtractionRequest request) {
        return ApiResponse.success(
                extractionService.retry(chapterId, generationId, batchId, request));
    }

    @PostMapping("/chapters/{chapterId}/generations/{generationId}/knowledge-extractions/{batchId}/cancel")
    public ApiResponse<AgentRunView> cancel(
            @PathVariable Long chapterId,
            @PathVariable Long generationId,
            @PathVariable Long batchId) {
        return ApiResponse.success(extractionService.cancel(chapterId, generationId, batchId));
    }

    @PostMapping("/knowledge-extraction-candidates/{candidateId}/confirm")
    public ApiResponse<CandidateDecision> confirm(
            @PathVariable Long candidateId,
            @RequestBody ConfirmCandidateRequest request) {
        return ApiResponse.success(extractionService.confirm(candidateId, request));
    }

    @PostMapping("/knowledge-extraction-candidates/{candidateId}/ignore")
    public ApiResponse<CandidateDecision> ignore(
            @PathVariable Long candidateId,
            @RequestBody IgnoreCandidateRequest request) {
        return ApiResponse.success(extractionService.ignore(candidateId, request));
    }
}
