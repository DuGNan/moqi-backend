package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.CandidateList;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.CandidateView;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.ResolveCandidateRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.ResolveScopeRequest;
import com.dugnan.moqi.chapter.service.ChapterConsensusScopeCandidateService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 提供章节共识作用域候选的查询和人工流转接口。
 */
@RestController
@RequestMapping("/api/chapter-consensus-scope-candidates")
public class ChapterConsensusScopeCandidateController {
    private final ChapterConsensusScopeCandidateService service;
    public ChapterConsensusScopeCandidateController(ChapterConsensusScopeCandidateService service) { this.service = service; }
    @GetMapping public ApiResponse<CandidateList> list(@RequestParam Long workId, @RequestParam(required = false) Long chapterId, @RequestParam(required = false) String status) { return ApiResponse.success(service.list(workId, chapterId, status)); }
    @PostMapping("/{id}/resolve-scope") public ApiResponse<CandidateView> resolve(@PathVariable Long id, @RequestBody ResolveScopeRequest request) { return ApiResponse.success(service.resolveUnknownScope(id, request)); }
    @PostMapping("/{id}/confirm") public ApiResponse<CandidateView> confirm(@PathVariable Long id, @RequestBody ResolveCandidateRequest request) { return ApiResponse.success(service.confirm(id, request)); }
    @PostMapping("/{id}/reject") public ApiResponse<CandidateView> reject(@PathVariable Long id, @RequestBody ResolveCandidateRequest request) { return ApiResponse.success(service.reject(id, request)); }
}
