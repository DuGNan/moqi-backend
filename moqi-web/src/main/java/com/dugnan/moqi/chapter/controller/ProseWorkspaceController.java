package com.dugnan.moqi.chapter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseCandidateDetail;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseWorkspaceView;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.SaveProseCandidateRequest;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.SaveWorkspaceSelectionRequest;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.WorkspaceSelectionView;
import com.dugnan.moqi.chapter.service.ProseWorkspaceService;
import com.dugnan.moqi.common.api.ApiResponse;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 提供统一章节正文工作区、稳定候选和选择恢复接口。
 */
@RestController
@RequestMapping("/api/chapters/{chapterId}")
public class ProseWorkspaceController {

    private final ProseWorkspaceService service;

    public ProseWorkspaceController(ProseWorkspaceService service) {
        this.service = service;
    }

    @GetMapping("/prose-workspace")
    public ApiResponse<ProseWorkspaceView> workspace(@PathVariable Long chapterId) {
        return ApiResponse.success(service.getWorkspace(chapterId));
    }

    @PutMapping("/prose-workspace/selection")
    public ApiResponse<WorkspaceSelectionView> saveSelection(
            @PathVariable Long chapterId,
            @RequestBody SaveWorkspaceSelectionRequest request) {
        return ApiResponse.success(service.saveSelection(chapterId, request));
    }

    @GetMapping("/prose-candidates/{candidateId}")
    public ApiResponse<ProseCandidateDetail> candidate(
            @PathVariable Long chapterId,
            @PathVariable Long candidateId) {
        return ApiResponse.success(service.getCandidate(chapterId, candidateId));
    }

    @PutMapping("/prose-candidates/{candidateId}")
    public ApiResponse<ProseCandidateDetail> saveCandidate(
            @PathVariable Long chapterId,
            @PathVariable Long candidateId,
            @RequestBody SaveProseCandidateRequest request) {
        return ApiResponse.success(service.saveCandidate(chapterId, candidateId, request));
    }
}
