package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.AdoptRequest;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.AdoptedTitleView;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.BatchView;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.CreateBatchRequest;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.LatestBatchView;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateModels.RetryRequest;
import com.dugnan.moqi.chapter.title.ChapterTitleCandidateService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 提供章节 AI 取名批次、恢复、取消和显式采用 HTTP 接口。
 */
@RestController
@RequestMapping("/api/chapters/{chapterId}/title-candidate-batches")
public class ChapterTitleCandidateController {

    private final ChapterTitleCandidateService service;

    public ChapterTitleCandidateController(ChapterTitleCandidateService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<BatchView> create(
            @PathVariable Long chapterId,
            @RequestBody CreateBatchRequest request) {
        return ApiResponse.success(service.create(chapterId, request));
    }

    @GetMapping("/latest")
    public ApiResponse<LatestBatchView> latest(
            @PathVariable Long chapterId,
            @RequestParam String sourceKind,
            @RequestParam String sourceObjectId) {
        return ApiResponse.success(service.latest(chapterId, sourceKind, sourceObjectId));
    }

    @GetMapping("/{batchId}")
    public ApiResponse<BatchView> get(@PathVariable Long chapterId, @PathVariable Long batchId) {
        return ApiResponse.success(service.get(chapterId, batchId));
    }

    @PostMapping("/{batchId}/retry")
    public ApiResponse<BatchView> retry(
            @PathVariable Long chapterId,
            @PathVariable Long batchId,
            @RequestBody RetryRequest request) {
        return ApiResponse.success(service.retry(chapterId, batchId, request));
    }

    @PostMapping("/{batchId}/cancel")
    public ApiResponse<BatchView> cancel(@PathVariable Long chapterId, @PathVariable Long batchId) {
        return ApiResponse.success(service.cancel(chapterId, batchId));
    }

    @PostMapping("/{batchId}/candidates/{candidateId}/adopt")
    public ApiResponse<AdoptedTitleView> adopt(
            @PathVariable Long chapterId,
            @PathVariable Long batchId,
            @PathVariable Long candidateId,
            @RequestBody AdoptRequest request) {
        return ApiResponse.success(service.adopt(chapterId, batchId, candidateId, request));
    }
}
